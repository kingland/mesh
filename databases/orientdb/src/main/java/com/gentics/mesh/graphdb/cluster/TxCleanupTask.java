package com.gentics.mesh.graphdb.cluster;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.gentics.madl.tx.AbstractTx;
import com.gentics.mesh.etc.config.GraphStorageOptions;
import com.gentics.mesh.etc.config.MeshOptions;
import com.gentics.mesh.util.Tuple;
import com.google.common.collect.ImmutableSet;

import io.vertx.core.Handler;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

@Singleton
public class TxCleanupTask implements Handler<Long> {

	private static final Logger log = LoggerFactory.getLogger(TxCleanupTask.class);

	private static final Map<Thread, Long> registeredThreads = new ConcurrentHashMap<>();

	private static final Set<ClassMethod> interruptedMethods = ImmutableSet.of(
		new ClassMethod(AbstractTx.class, "commit"));

	private GraphStorageOptions storageOptions;

	@Inject
	public TxCleanupTask(MeshOptions options) {
		this.storageOptions = options.getStorageOptions();
	}

	@Override
	public void handle(Long event) {
		checkTransactions();
	}

	private boolean isCommitting(StackTraceElement[] stackTrace) {
		return Stream.of(stackTrace)
			.map(ClassMethod::of)
			.anyMatch(interruptedMethods::contains);
	}

	/**
	 * Check whether there are any transactions which exceed the set time limit.
	 */
	public void checkTransactions() {
		log.info("Checking {} transaction threads", registeredThreads.size());
		List<Thread> toInterrupt = registeredThreads.entrySet()
			.stream().filter(entry -> {
				long now = System.currentTimeMillis();
				long dur = now - entry.getValue();
				// TODO configure duration
				long limit = 20_000;
				boolean exceedsLimit = dur > limit;
				if (exceedsLimit) {
					log.info("Thread {} exceeds time limit of {} with duration {}.", entry.getKey(), limit, dur);
				}
				return exceedsLimit;
			}).map(entry -> {
				Thread t = entry.getKey();
				return Tuple.tuple(t, t.getStackTrace());
			})
			.filter(tuple -> isCommitting(tuple.v2()))
			.map(Tuple::v1)
			.collect(Collectors.toList());

		log.info("Interrupting {} threads", toInterrupt.size());
		for (Thread thread : toInterrupt) {
			log.info("Interrupting transaction thread {}", thread.getName());
			thread.interrupt();
		}

	}

	public static class ClassMethod {
		private final String className;
		private final String methodName;

		public ClassMethod(Class<?> clazz, String methodName) {
			this(clazz.getName(), methodName);
		}

		public ClassMethod(String className, String methodName) {
			this.className = className;
			this.methodName = methodName;
		}

		public static ClassMethod of(StackTraceElement element) {
			return new ClassMethod(element.getClassName(), element.getMethodName());
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			ClassMethod that = (ClassMethod) o;
			return Objects.equals(className, that.className) &&
				Objects.equals(methodName, that.methodName);
		}

		@Override
		public int hashCode() {
			return Objects.hash(className, methodName);
		}
	}

	public static void register(Thread thread) {
		registeredThreads.put(thread, System.currentTimeMillis());
	}

	public static void unregister(Thread thread) {
		registeredThreads.remove(thread);

	}
}
