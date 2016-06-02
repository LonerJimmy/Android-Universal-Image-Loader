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

import android.view.View;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.assist.FlushedInputStream;
import com.nostra13.universalimageloader.core.imageaware.ImageAware;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@link ImageLoader} engine which responsible for {@linkplain LoadAndDisplayImageTask display task} execution.
 *
 * @author Sergey Tarasevich (nostra13[at]gmail[dot]com)
 * @since 1.7.1
 *
 * 分发LoadAndDisplayImageTask和ProcessAndDisplayImageTask给具体的线程去执行
 */
class ImageLoaderEngine {

	final ImageLoaderConfiguration configuration;

	//用于执行从源获取图片任务的excutor
	private Executor taskExecutor;
	//用于执行从缓存中获取图片任务的excutor
	private Executor taskExecutorForCachedImages;
	//任务分发线程池,任务是LoadAndDisplayImageTask和LoadAndDisplayImageTask,只需要分发给上面两个excutor来执行就ok
	private Executor taskDistributor;

	//ImageAware与内存缓存key对应的map,key为ImageAware的id,value为内存缓存中的key.
	private final Map<Integer, String> cacheKeysForImageAwares = Collections
			.synchronizedMap(new HashMap<Integer, String>());
	//图片正在加载的重入锁 map，key 为图片的 uri，value 为标识其正在加载的重入锁。
	private final Map<String, ReentrantLock> uriLocks = new WeakHashMap<String, ReentrantLock>();

	//是否被暂停,如果为true,所有加载或者显示任务都会暂停,直到取消暂停
	private final AtomicBoolean paused = new AtomicBoolean(false);
	//是否允许访问网络
	private final AtomicBoolean networkDenied = new AtomicBoolean(false);
	//是否是网络慢的情况
	private final AtomicBoolean slowNetwork = new AtomicBoolean(false);

	//暂停的等待锁
	private final Object pauseLock = new Object();

	ImageLoaderEngine(ImageLoaderConfiguration configuration) {
		this.configuration = configuration;

		taskExecutor = configuration.taskExecutor;
		taskExecutorForCachedImages = configuration.taskExecutorForCachedImages;

		taskDistributor = DefaultConfigurationFactory.createTaskDistributor();
	}

	/** Submits task to execution pool
	 *
	 * 分发LoadAndDisplayImageTask给excution pool
	 *
	 * */
	void submit(final LoadAndDisplayImageTask task) {
		taskDistributor.execute(new Runnable() {
			@Override
			public void run() {
				File image = configuration.diskCache.get(task.getLoadingUri());
				boolean isImageCachedOnDisk = image != null && image.exists();
				initExecutorsIfNeed();
				if (isImageCachedOnDisk) {
					taskExecutorForCachedImages.execute(task);
				} else {
					taskExecutor.execute(task);
				}
			}
		});
	}

	/** Submits task to execution pool
	 *
	 * 分发ProcessAndDisplayImageTask给excution pool
	 *
	 * */
	void submit(ProcessAndDisplayImageTask task) {
		initExecutorsIfNeed();
		taskExecutorForCachedImages.execute(task);
	}

	private void initExecutorsIfNeed() {
		if (!configuration.customExecutor && ((ExecutorService) taskExecutor).isShutdown()) {
			taskExecutor = createTaskExecutor();
		}
		if (!configuration.customExecutorForCachedImages && ((ExecutorService) taskExecutorForCachedImages)
				.isShutdown()) {
			taskExecutorForCachedImages = createTaskExecutor();
		}
	}

	//创建线程池
	private Executor createTaskExecutor() {
		return DefaultConfigurationFactory
				.createExecutor(configuration.threadPoolSize, configuration.threadPriority,
				configuration.tasksProcessingType);
	}

	/**
	 * Returns URI of image which is loading at this moment into passed {@link com.nostra13.universalimageloader.core.imageaware.ImageAware}
	 *
	 * 得到某个ImageAware正在加载的图片uri
	 *
	 */
	String getLoadingUriForView(ImageAware imageAware) {
		return cacheKeysForImageAwares.get(imageAware.getId());
	}

	/**
	 * Associates <b>memoryCacheKey</b> with <b>imageAware</b>. Then it helps to define image URI is loaded into View at
	 * exact moment.
	 *
	 * 准备开始一个task,向cacheKeysForImageAwares中插入imageaware的id和图片在内存缓存中的key
	 *
	 */
	void prepareDisplayTaskFor(ImageAware imageAware, String memoryCacheKey) {
		cacheKeysForImageAwares.put(imageAware.getId(), memoryCacheKey);
	}

	/**
	 * Cancels the task of loading and displaying image for incoming <b>imageAware</b>.
	 *
	 * @param imageAware {@link com.nostra13.universalimageloader.core.imageaware.ImageAware} for which display task
	 *                   will be cancelled
	 *
	 * 取消一个显示任务,从cacheKeysForImageAwares中删除imageAware
	 *
	 */
	void cancelDisplayTaskFor(ImageAware imageAware) {
		cacheKeysForImageAwares.remove(imageAware.getId());
	}

	/**
	 * Denies or allows engine to download images from the network.<br /> <br /> If downloads are denied and if image
	 * isn't cached then {@link ImageLoadingListener#onLoadingFailed(String, View, FailReason)} callback will be fired
	 * with {@link FailReason.FailType#NETWORK_DENIED}
	 *
	 * @param denyNetworkDownloads pass <b>true</b> - to deny engine to download images from the network; <b>false</b> -
	 *                             to allow engine to download images from network.
	 */
	void denyNetworkDownloads(boolean denyNetworkDownloads) {
		networkDenied.set(denyNetworkDownloads);
	}

	/**
	 * Sets option whether ImageLoader will use {@link FlushedInputStream} for network downloads to handle <a
	 * href="http://code.google.com/p/android/issues/detail?id=6066">this known problem</a> or not.
	 *
	 * @param handleSlowNetwork pass <b>true</b> - to use {@link FlushedInputStream} for network downloads; <b>false</b>
	 *                          - otherwise.
	 */
	void handleSlowNetwork(boolean handleSlowNetwork) {
		slowNetwork.set(handleSlowNetwork);
	}

	/**
	 * Pauses engine. All new "load&display" tasks won't be executed until ImageLoader is {@link #resume() resumed}.<br
	 * /> Already running tasks are not paused.
	 *
	 * 暂停图片加载
	 *
	 */
	void pause() {
		paused.set(true);
	}

	/** Resumes engine work. Paused "load&display" tasks will continue its work.
	 *
	 * 继续图片加载
	 *
	 * */
	void resume() {
		paused.set(false);
		synchronized (pauseLock) {
			pauseLock.notifyAll();
		}
	}

	/**
	 * Stops engine, cancels all running and scheduled display image tasks. Clears internal data.
	 * <br />
	 * <b>NOTE:</b> This method doesn't shutdown
	 * {@linkplain com.nostra13.universalimageloader.core.ImageLoaderConfiguration.Builder#taskExecutor(java.util.concurrent.Executor)
	 * custom task executors} if you set them.
	 */
	void stop() {
		if (!configuration.customExecutor) {
			((ExecutorService) taskExecutor).shutdownNow();
		}
		if (!configuration.customExecutorForCachedImages) {
			((ExecutorService) taskExecutorForCachedImages).shutdownNow();
		}

		cacheKeysForImageAwares.clear();
		uriLocks.clear();
	}

	//执行taskDistributor
	void fireCallback(Runnable r) {
		taskDistributor.execute(r);
	}

	ReentrantLock getLockForUri(String uri) {
		ReentrantLock lock = uriLocks.get(uri);
		if (lock == null) {
			lock = new ReentrantLock();
			uriLocks.put(uri, lock);
		}
		return lock;
	}

	AtomicBoolean getPause() {
		return paused;
	}

	Object getPauseLock() {
		return pauseLock;
	}

	boolean isNetworkDenied() {
		return networkDenied.get();
	}

	boolean isSlowNetwork() {
		return slowNetwork.get();
	}
}
