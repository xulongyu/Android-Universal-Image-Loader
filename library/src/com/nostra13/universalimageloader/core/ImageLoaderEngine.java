package com.nostra13.universalimageloader.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import android.widget.ImageView;

import com.nostra13.universalimageloader.core.assist.QueueProcessingType;
import com.nostra13.universalimageloader.core.assist.deque.LIFOLinkedBlockingDeque;

/**
 * {@link ImageLoader} engine which responsible for {@linkplain LoadAndDisplayImageTask display task} execution.
 * 
 * @author Sergey Tarasevich
 * @created 01.02.2013
 */
class ImageLoaderEngine {

	final ImageLoaderConfiguration configuration;

	private ExecutorService imageLoadingExecutor;
	private ExecutorService cachedImageLoadingExecutor;
	private ExecutorService taskDistributor;

	private final Map<Integer, String> cacheKeysForImageViews = Collections.synchronizedMap(new HashMap<Integer, String>());
	private final Map<String, ReentrantLock> uriLocks = new WeakHashMap<String, ReentrantLock>();

	private final AtomicBoolean paused = new AtomicBoolean(false);

	ImageLoaderEngine(ImageLoaderConfiguration configuration) {
		this.configuration = configuration;
	}

	/** Submits display task to execution pool */
	void submit(final LoadAndDisplayImageTask task) {
		initExecutorsIfNeed();
		taskDistributor.submit(new Runnable() {
			@Override
			public void run() {
				boolean isImageCachedOnDisc = configuration.discCache.get(task.getLoadingUri()).exists();
				if (isImageCachedOnDisc) {
					cachedImageLoadingExecutor.submit(task);
				} else {
					imageLoadingExecutor.submit(task);
				}
			}
		});
	}

	private void initExecutorsIfNeed() {
		if (imageLoadingExecutor == null || imageLoadingExecutor.isShutdown()) {
			imageLoadingExecutor = createTaskExecutor();
		}
		if (cachedImageLoadingExecutor == null || cachedImageLoadingExecutor.isShutdown()) {
			cachedImageLoadingExecutor = createTaskExecutor();
		}
		if (taskDistributor == null || taskDistributor.isShutdown()) {
			taskDistributor = Executors.newCachedThreadPool();
		}
	}

	private ExecutorService createTaskExecutor() {
		boolean lifo = configuration.tasksProcessingType == QueueProcessingType.LIFO;
		BlockingQueue<Runnable> taskQueue = lifo ? new LIFOLinkedBlockingDeque<Runnable>() : new LinkedBlockingQueue<Runnable>();
		return new ThreadPoolExecutor(configuration.threadPoolSize, configuration.threadPoolSize, 0L, TimeUnit.MILLISECONDS, taskQueue,
				configuration.displayImageThreadFactory);
	}

	/** Returns URI of image which is loading at this moment into passed {@link ImageView} */
	String getLoadingUriForView(ImageView imageView) {
		return cacheKeysForImageViews.get(imageView.hashCode());
	}

	/**
	 * Associates <b>memoryCacheKey</b> with <b>imageView</b>. Then it helps to define image URI is loaded into
	 * ImageView at exact moment.
	 */
	void prepareDisplayTaskFor(ImageView imageView, String memoryCacheKey) {
		cacheKeysForImageViews.put(imageView.hashCode(), memoryCacheKey);
	}

	/**
	 * Cancels the task of loading and displaying image for incoming <b>imageView</b>.
	 * 
	 * @param imageView {@link ImageView} for which display task will be cancelled
	 */
	void cancelDisplayTaskFor(ImageView imageView) {
		cacheKeysForImageViews.remove(imageView.hashCode());
	}

	/**
	 * Pauses engine. All new "load&display" tasks won't be executed until ImageLoader is {@link #resume() resumed}.<br />
	 * Already running tasks are not paused.
	 */
	void pause() {
		paused.set(true);
	}

	/** Resumes engine work. Paused "load&display" tasks will continue its work. */
	void resume() {
		synchronized (paused) {
			paused.set(false);
			paused.notifyAll();
		}
	}

	/** Stops all running display image tasks, discards all other scheduled tasks */
	void stop() {
		if (imageLoadingExecutor != null) {
			imageLoadingExecutor.shutdownNow();
		}
		if (cachedImageLoadingExecutor != null) {
			cachedImageLoadingExecutor.shutdownNow();
		}
		if (taskDistributor != null) {
			taskDistributor.shutdownNow();
		}
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
}
