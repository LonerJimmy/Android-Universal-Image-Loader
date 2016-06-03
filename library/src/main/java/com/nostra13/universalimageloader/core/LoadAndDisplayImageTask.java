/*******************************************************************************
 * Copyright 2011-2014 Sergey Tarasevich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.nostra13.universalimageloader.core;

import android.graphics.Bitmap;
import android.os.Handler;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FailReason.FailType;
import com.nostra13.universalimageloader.core.assist.ImageScaleType;
import com.nostra13.universalimageloader.core.assist.ImageSize;
import com.nostra13.universalimageloader.core.assist.LoadedFrom;
import com.nostra13.universalimageloader.core.assist.ViewScaleType;
import com.nostra13.universalimageloader.core.decode.ImageDecoder;
import com.nostra13.universalimageloader.core.decode.ImageDecodingInfo;
import com.nostra13.universalimageloader.core.download.ImageDownloader;
import com.nostra13.universalimageloader.core.download.ImageDownloader.Scheme;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;
import com.nostra13.universalimageloader.core.listener.ImageLoadingProgressListener;
import com.nostra13.universalimageloader.utils.IoUtils;
import com.nostra13.universalimageloader.utils.L;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Presents load'n'display image task. Used to load image from Internet or file system, decode it to {@link Bitmap}, and
 * display it in {@link com.nostra13.universalimageloader.core.imageaware.ImageAware} using {@link DisplayBitmapTask}.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @see ImageLoaderConfiguration
 * @see ImageLoadingInfo
 * @since 1.3.1
 */
final class LoadAndDisplayImageTask implements Runnable, IoUtils.CopyListener {

	private static final String LOG_WAITING_FOR_RESUME = "ImageLoader is paused. Waiting...  [%s]";
	private static final String LOG_RESUME_AFTER_PAUSE = ".. Resume loading [%s]";
	private static final String LOG_DELAY_BEFORE_LOADING = "Delay %d ms before loading...  [%s]";
	private static final String LOG_START_DISPLAY_IMAGE_TASK = "Start display image task [%s]";
	private static final String LOG_WAITING_FOR_IMAGE_LOADED = "Image already is loading. Waiting... [%s]";
	private static final String LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING = "...Get cached bitmap from memory after waiting. [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_NETWORK = "Load image from network [%s]";
	private static final String LOG_LOAD_IMAGE_FROM_DISK_CACHE = "Load image from disk cache [%s]";
	private static final String LOG_RESIZE_CACHED_IMAGE_FILE = "Resize image in disk cache [%s]";
	private static final String LOG_PREPROCESS_IMAGE = "PreProcess image before caching in memory [%s]";
	private static final String LOG_POSTPROCESS_IMAGE = "PostProcess image before displaying [%s]";
	private static final String LOG_CACHE_IMAGE_IN_MEMORY = "Cache image in memory [%s]";
	private static final String LOG_CACHE_IMAGE_ON_DISK = "Cache image on disk [%s]";
	private static final String LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISK = "Process image before cache on disk [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEAWARE_REUSED = "ImageAware is reused for another image. Task is cancelled. [%s]";
	private static final String LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED = "ImageAware was collected by GC. Task is cancelled. [%s]";
	private static final String LOG_TASK_INTERRUPTED = "Task was interrupted [%s]";

	private static final String ERROR_NO_IMAGE_STREAM = "No stream for image [%s]";
	private static final String ERROR_PRE_PROCESSOR_NULL = "Pre-processor returned null [%s]";
	private static final String ERROR_POST_PROCESSOR_NULL = "Post-processor returned null [%s]";
	private static final String ERROR_PROCESSOR_FOR_DISK_CACHE_NULL = "Bitmap processor for disk cache returned null [%s]";

	private final ImageLoaderEngine engine;
	private final ImageLoadingInfo imageLoadingInfo;
	private final Handler handler;

	// Helper references
	private final ImageLoaderConfiguration configuration;
	private final ImageDownloader downloader;
	private final ImageDownloader networkDeniedDownloader;
	private final ImageDownloader slowNetworkDownloader;
	private final ImageDecoder decoder;
	final String uri;
	private final String memoryCacheKey;
	final ImageAware imageAware;
	private final ImageSize targetSize;
	final DisplayImageOptions options;
	final ImageLoadingListener listener;
	final ImageLoadingProgressListener progressListener;
	private final boolean syncLoading;

	// State vars
	private LoadedFrom loadedFrom = LoadedFrom.NETWORK;

	public LoadAndDisplayImageTask(ImageLoaderEngine engine, ImageLoadingInfo imageLoadingInfo, Handler handler) {
		this.engine = engine;
		this.imageLoadingInfo = imageLoadingInfo;
		this.handler = handler;

		configuration = engine.configuration;
		downloader = configuration.downloader;
		networkDeniedDownloader = configuration.networkDeniedDownloader;
		slowNetworkDownloader = configuration.slowNetworkDownloader;
		decoder = configuration.decoder;
		uri = imageLoadingInfo.uri;
		memoryCacheKey = imageLoadingInfo.memoryCacheKey;
		imageAware = imageLoadingInfo.imageAware;
		targetSize = imageLoadingInfo.targetSize;
		options = imageLoadingInfo.options;
		listener = imageLoadingInfo.listener;
		progressListener = imageLoadingInfo.progressListener;
		syncLoading = options.isSyncLoading();
	}

	/**
	 * 从内存缓存中读取bitmap对象,若不存在,调用tryLoadBitmap()函数获取bitmap对象,获取成功,如果options.isCacheInMemory()为true,将bitmap对象缓存到内存中.
	 * 最后在DisplayBitmapTask显示图片,
	 * DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
	 * runTask(displayBitmapTask, syncLoading, handler, engine);
	 */
	@Override
	public void run() {
		if (waitIfPaused()) return;
		if (delayIfNeed()) return;

		//得到互斥锁
		ReentrantLock loadFromUriLock = imageLoadingInfo.loadFromUriLock;
		L.d(LOG_START_DISPLAY_IMAGE_TASK, memoryCacheKey);
		if (loadFromUriLock.isLocked()) {
			L.d(LOG_WAITING_FOR_IMAGE_LOADED, memoryCacheKey);
		}

		//不同线程去加载图片,保证每次线程处理完成之后,其他线程才可以进来进行处理.

		//启动锁
		loadFromUriLock.lock();
		Bitmap bmp;
		try {
			checkTaskNotActual();
			//从内存中获取图片
			bmp = configuration.memoryCache.get(memoryCacheKey);
			if (bmp == null || bmp.isRecycled()) {
				bmp = tryLoadBitmap();
				if (bmp == null) return; // listener callback already was fired

				checkTaskNotActual();
				checkTaskInterrupted();

				if (options.shouldPreProcess()) {
					L.d(LOG_PREPROCESS_IMAGE, memoryCacheKey);
					bmp = options.getPreProcessor().process(bmp);
					if (bmp == null) {
						L.e(ERROR_PRE_PROCESSOR_NULL, memoryCacheKey);
					}
				}

				//缓存到内存中
				if (bmp != null && options.isCacheInMemory()) {
					L.d(LOG_CACHE_IMAGE_IN_MEMORY, memoryCacheKey);
					configuration.memoryCache.put(memoryCacheKey, bmp);
				}
			} else {
				loadedFrom = LoadedFrom.MEMORY_CACHE;
				L.d(LOG_GET_IMAGE_FROM_MEMORY_CACHE_AFTER_WAITING, memoryCacheKey);
			}

			if (bmp != null && options.shouldPostProcess()) {
				L.d(LOG_POSTPROCESS_IMAGE, memoryCacheKey);
				bmp = options.getPostProcessor().process(bmp);
				if (bmp == null) {
					L.e(ERROR_POST_PROCESSOR_NULL, memoryCacheKey);
				}
			}
			checkTaskNotActual();
			checkTaskInterrupted();
		} catch (TaskCancelledException e) {
			fireCancelEvent();
			return;
		} finally {
			//释放锁
			loadFromUriLock.unlock();
		}

		//最终执行了DisplayBitmapTask,获取最后的bitmap显示到界面上
		DisplayBitmapTask displayBitmapTask = new DisplayBitmapTask(bmp, imageLoadingInfo, engine, loadedFrom);
		runTask(displayBitmapTask, syncLoading, handler, engine);
	}

	/** @return <b>true</b> - if task should be interrupted; <b>false</b> - otherwise */
	private boolean waitIfPaused() {
		AtomicBoolean pause = engine.getPause();
		if (pause.get()) {
			synchronized (engine.getPauseLock()) {
				if (pause.get()) {
					L.d(LOG_WAITING_FOR_RESUME, memoryCacheKey);
					try {
						engine.getPauseLock().wait();
					} catch (InterruptedException e) {
						L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
						return true;
					}
					L.d(LOG_RESUME_AFTER_PAUSE, memoryCacheKey);
				}
			}
		}
		return isTaskNotActual();
	}

	/** @return <b>true</b> - if task should be interrupted; <b>false</b> - otherwise */
	private boolean delayIfNeed() {
		if (options.shouldDelayBeforeLoading()) {
			L.d(LOG_DELAY_BEFORE_LOADING, options.getDelayBeforeLoading(), memoryCacheKey);
			try {
				Thread.sleep(options.getDelayBeforeLoading());
			} catch (InterruptedException e) {
				L.e(LOG_TASK_INTERRUPTED, memoryCacheKey);
				return true;
			}
			return isTaskNotActual();
		}
		return false;
	}

	/**
	 *
	 * @return
	 * @throws TaskCancelledException
	 *
	 * 判断磁盘是否存在图片,如果存在,直接从磁盘加载图片,如果本地没有,就要从网络获取照片
     */
	private Bitmap tryLoadBitmap() throws TaskCancelledException {
		Bitmap bitmap = null;
		try {
			//从磁盘缓存中获取图片
			File imageFile = configuration.diskCache.get(uri);
			if (imageFile != null && imageFile.exists() && imageFile.length() > 0) {
				L.d(LOG_LOAD_IMAGE_FROM_DISK_CACHE, memoryCacheKey);
				loadedFrom = LoadedFrom.DISC_CACHE;

				checkTaskNotActual();
				//从磁盘中加载图片
				bitmap = decodeImage(Scheme.FILE.wrap(imageFile.getAbsolutePath()));
			}
			if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
				L.d(LOG_LOAD_IMAGE_FROM_NETWORK, memoryCacheKey);
				loadedFrom = LoadedFrom.NETWORK;

				String imageUriForDecoding = uri;
				//将图片缓存到磁盘
				//trycacheiamgeondisk去加载网络照片
				if (options.isCacheOnDisk() && tryCacheImageOnDisk()) {
					imageFile = configuration.diskCache.get(uri);
					if (imageFile != null) {
						//将url替换成图片在本地磁盘地址
						imageUriForDecoding = Scheme.FILE.wrap(imageFile.getAbsolutePath());
					}
				}

				checkTaskNotActual();
				// 如果上面允许缓存， 则在上面就加载了图片
				// 并且将imageUriForDecoding替换成了本地uri
				// 否则这里是去加载网络图片
				bitmap = decodeImage(imageUriForDecoding);

				if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
					fireFailEvent(FailType.DECODING_ERROR, null);
				}
			}
		} catch (IllegalStateException e) {
			fireFailEvent(FailType.NETWORK_DENIED, null);
		} catch (TaskCancelledException e) {
			throw e;
		} catch (IOException e) {
			L.e(e);
			fireFailEvent(FailType.IO_ERROR, e);
		} catch (OutOfMemoryError e) {
			L.e(e);
			fireFailEvent(FailType.OUT_OF_MEMORY, e);
		} catch (Throwable e) {
			L.e(e);
			fireFailEvent(FailType.UNKNOWN, e);
		}
		return bitmap;
	}

	//图片已经从网络下载到本地了.
	private Bitmap decodeImage(String imageUri) throws IOException {
		ViewScaleType viewScaleType = imageAware.getScaleType();
		ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey, imageUri, uri, targetSize, viewScaleType,
				getDownloader(), options);
		//decode实现,看一波BaseImageDecocder
		return decoder.decode(decodingInfo);
	}

	/**
	 * @return <b>true</b> - if image was downloaded successfully;
	 * <b>false</b>
	 * otherwise
	 *
	 * 下载图片并存储在磁盘内，根据磁盘缓存图片最长宽高的配置处理图片。
	 *
	 */

	private boolean tryCacheImageOnDisk() throws TaskCancelledException {
		L.d(LOG_CACHE_IMAGE_ON_DISK, memoryCacheKey);

		boolean loaded;
		try {
			//调用下载器下载并保存图片
			loaded = downloadImage();
			if (loaded) {
				int width = configuration.maxImageWidthForDiskCache;
				int height = configuration.maxImageHeightForDiskCache;
				if (width > 0 || height > 0) {
					L.d(LOG_RESIZE_CACHED_IMAGE_FILE, memoryCacheKey);
					resizeAndSaveImage(width, height); // TODO : process boolean result
				}
			}
		} catch (IOException e) {
			L.e(e);
			loaded = false;
		}
		return loaded;
	}

	//下载图片并存储在磁盘内
	private boolean downloadImage() throws IOException {
		//从Downloader中获取一个stream
		//imagedownloader,看一波BaseImageDownLoader
		InputStream is = getDownloader().getStream(uri, options.getExtraForDownloader());
		if (is == null) {
			L.e(ERROR_NO_IMAGE_STREAM, memoryCacheKey);
			return false;
		} else {
			try {
				//BaseDiskCache.save方法
				return configuration.diskCache.save(uri, is, this);
			} finally {
				IoUtils.closeSilently(is);
			}
		}
	}

	/** Decodes image file into Bitmap, resize it and save it back */
	private boolean resizeAndSaveImage(int maxWidth, int maxHeight) throws IOException {
		// Decode image file, compress and re-save it
		boolean saved = false;
		File targetFile = configuration.diskCache.get(uri);
		if (targetFile != null && targetFile.exists()) {
			ImageSize targetImageSize = new ImageSize(maxWidth, maxHeight);
			DisplayImageOptions specialOptions = new DisplayImageOptions.Builder().cloneFrom(options)
					.imageScaleType(ImageScaleType.IN_SAMPLE_INT).build();
			ImageDecodingInfo decodingInfo = new ImageDecodingInfo(memoryCacheKey,
					Scheme.FILE.wrap(targetFile.getAbsolutePath()), uri, targetImageSize, ViewScaleType.FIT_INSIDE,
					getDownloader(), specialOptions);
			Bitmap bmp = decoder.decode(decodingInfo);
			if (bmp != null && configuration.processorForDiskCache != null) {
				L.d(LOG_PROCESS_IMAGE_BEFORE_CACHE_ON_DISK, memoryCacheKey);
				bmp = configuration.processorForDiskCache.process(bmp);
				if (bmp == null) {
					L.e(ERROR_PROCESSOR_FOR_DISK_CACHE_NULL, memoryCacheKey);
				}
			}
			if (bmp != null) {
				saved = configuration.diskCache.save(uri, bmp);
				bmp.recycle();
			}
		}
		return saved;
	}

	@Override
	public boolean onBytesCopied(int current, int total) {
		return syncLoading || fireProgressEvent(current, total);
	}

	/** @return <b>true</b> - if loading should be continued; <b>false</b> - if loading should be interrupted */
	private boolean fireProgressEvent(final int current, final int total) {
		if (isTaskInterrupted() || isTaskNotActual()) return false;
		if (progressListener != null) {
			Runnable r = new Runnable() {
				@Override
				public void run() {
					progressListener.onProgressUpdate(uri, imageAware.getWrappedView(), current, total);
				}
			};
			runTask(r, false, handler, engine);
		}
		return true;
	}

	private void fireFailEvent(final FailType failType, final Throwable failCause) {
		if (syncLoading || isTaskInterrupted() || isTaskNotActual()) return;
		Runnable r = new Runnable() {
			@Override
			public void run() {
				if (options.shouldShowImageOnFail()) {
					imageAware.setImageDrawable(options.getImageOnFail(configuration.resources));
				}
				listener.onLoadingFailed(uri, imageAware.getWrappedView(), new FailReason(failType, failCause));
			}
		};
		runTask(r, false, handler, engine);
	}

	private void fireCancelEvent() {
		if (syncLoading || isTaskInterrupted()) return;
		Runnable r = new Runnable() {
			@Override
			public void run() {
				listener.onLoadingCancelled(uri, imageAware.getWrappedView());
			}
		};
		runTask(r, false, handler, engine);
	}

	/**
	 * 根据ImageLoaderEngine配置得到下载器。
	 * 如果不允许访问网络，则使用不允许访问网络的图片下载器NetworkDeniedImageDownloader；
	 * 如果是慢网络情况，则使用慢网络情况下的图片下载器SlowNetworkImageDownloader；
	 * 否则直接使用ImageLoaderConfiguration中的downloader。
	 **/
	private ImageDownloader getDownloader() {
		ImageDownloader d;
		if (engine.isNetworkDenied()) {
			d = networkDeniedDownloader;
		} else if (engine.isSlowNetwork()) {
			d = slowNetworkDownloader;
		} else {
			d = downloader;
		}
		return d;
	}

	/**
	 * @throws TaskCancelledException if task is not actual (target ImageAware is collected by GC or the image URI of
	 *                                this task doesn't match to image URI which is actual for current ImageAware at
	 *                                this moment)
	 */
	private void checkTaskNotActual() throws TaskCancelledException {
		checkViewCollected();
		checkViewReused();
	}

	/**
	 * @return <b>true</b> - if task is not actual (target ImageAware is collected by GC or the image URI of this task
	 * doesn't match to image URI which is actual for current ImageAware at this moment)); <b>false</b> - otherwise
	 */
	private boolean isTaskNotActual() {
		return isViewCollected() || isViewReused();
	}

	/** @throws TaskCancelledException if target ImageAware is collected */
	private void checkViewCollected() throws TaskCancelledException {
		if (isViewCollected()) {
			throw new TaskCancelledException();
		}
	}

	/** @return <b>true</b> - if target ImageAware is collected by GC; <b>false</b> - otherwise */
	private boolean isViewCollected() {
		if (imageAware.isCollected()) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_COLLECTED, memoryCacheKey);
			return true;
		}
		return false;
	}

	/** @throws TaskCancelledException if target ImageAware is collected by GC */
	private void checkViewReused() throws TaskCancelledException {
		if (isViewReused()) {
			throw new TaskCancelledException();
		}
	}

	/** @return <b>true</b> - if current ImageAware is reused for displaying another image; <b>false</b> - otherwise
	 *
	 * 判断ImageAware是否被复用
	 *
	 * */
	private boolean isViewReused() {
		String currentCacheKey = engine.getLoadingUriForView(imageAware);
		// Check whether memory cache key (image URI) for current ImageAware is actual.
		// If ImageAware is reused for another task then current task should be cancelled.
		boolean imageAwareWasReused = !memoryCacheKey.equals(currentCacheKey);
		if (imageAwareWasReused) {
			L.d(LOG_TASK_CANCELLED_IMAGEAWARE_REUSED, memoryCacheKey);
			return true;
		}
		return false;
	}

	/** @throws TaskCancelledException if current task was interrupted */
	private void checkTaskInterrupted() throws TaskCancelledException {
		if (isTaskInterrupted()) {
			throw new TaskCancelledException();
		}
	}

	/** @return <b>true</b> - if current task was interrupted; <b>false</b> - otherwise */
	private boolean isTaskInterrupted() {
		if (Thread.interrupted()) {
			L.d(LOG_TASK_INTERRUPTED, memoryCacheKey);
			return true;
		}
		return false;
	}

	String getLoadingUri() {
		return uri;
	}

	static void runTask(Runnable r, boolean sync, Handler handler, ImageLoaderEngine engine) {
		if (sync) {
			r.run();
		} else if (handler == null) {
			engine.fireCallback(r);
		} else {
			handler.post(r);
		}
	}

	/**
	 * Exceptions for case when task is cancelled (thread is interrupted, image view is reused for another task, view is
	 * collected by GC).
	 *
	 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
	 * @since 1.9.1
	 */
	class TaskCancelledException extends Exception {
	}
}
