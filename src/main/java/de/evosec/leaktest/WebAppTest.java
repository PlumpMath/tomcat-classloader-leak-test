package de.evosec.leaktest;

import static com.jayway.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import java.util.concurrent.Callable;

import javax.servlet.ServletException;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.core.JreMemoryLeakPreventionListener;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.ThreadLocalLeakPreventionListener;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.StandardRoot;

import com.jayway.awaitility.Duration;
import com.jayway.awaitility.core.ConditionTimeoutException;

import javassist.ClassPool;
import javassist.CtClass;

public class WebAppTest {

	static {
		for (MemoryPoolMXBean mbean : ManagementFactory
		    .getMemoryPoolMXBeans()) {
			if ("Metaspace".equals(mbean.getName())
			        && mbean.getUsage().getMax() == -1) {
				throw new IllegalStateException(
				    "MaxMetaspaceSize is undefined. Include -XX:MaxMetaspaceSize=128m in JVM arguments.");
			}
		}
	}

	private Path catalinaBase;
	private Path warPath;
	private String pingEndPoint = "";
	private long deployDuration = 10;
	private Path contextPath;
	private boolean testLeak = true;

	private Tomcat tomcat;
	private DestroyListener destroyListener;
	private Context context;
	private WeakReference<ClassLoader> classLoaderReference;
	private int port;

	public WebAppTest warPath(Path warPath) {
		this.warPath = warPath;
		return this;
	}

	public WebAppTest pingEndPoint(String pingEndPoint) {
		this.pingEndPoint = pingEndPoint;
		return this;
	}

	public WebAppTest deployDuration(long deployDuration) {
		this.deployDuration = deployDuration;
		return this;
	}

	public WebAppTest contextPath(Path contextPath) {
		this.contextPath = contextPath;
		return this;
	}

	public WebAppTest testLeak(boolean testLeak) {
		this.testLeak = testLeak;
		return this;
	}

	public int getPort() {
		return port;
	}

	public void start() throws WebAppTestException {
		checkArguments();

		tomcat = null;
		destroyListener = new DestroyListener();
		try {
			tomcat = getTomcatInstance();

			context = tomcat.addWebapp("/test",
			    warPath.toAbsolutePath().toString());

			configureContext();

			configureTomcat();

			tomcat.start();

			checkContextStarted();

			classLoaderReference =
			        new WeakReference<>(context.getLoader().getClassLoader());

			port = tomcat.getConnector().getLocalPort();

			ping(new URL("http", "localhost", port, "/test/" + pingEndPoint));

		} catch (IOException | ServletException | LifecycleException e) {
			shutdownTomcat();
			throw new WebAppTestException(e);
		}
	}

	public void stop() throws WebAppTestException {
		try {
			if (context != null) {
				tomcat.getHost().removeChild(context);
				// it is unnecessary to check whether the context was stopped
				// since removeChild is a blocking call
				context = null;
			}

			testLeak();
		} finally {
			shutdownTomcat();
		}
	}

	public void run() throws WebAppTestException {
		try {
			start();
			stop();
		} finally {
			shutdownTomcat();
		}
	}

	private void checkArguments() {
		if (warPath == null) {
			throw new IllegalArgumentException("warFile cannot be null");
		}
		if (pingEndPoint == null) {
			throw new IllegalArgumentException("pingEndPoint cannot be null");
		}
		if (!Files.exists(warPath)) {
			throw new IllegalArgumentException(
			    "WAR file does not exist: " + warPath);
		}
	}

	private void checkContextStarted() throws LifecycleException {
		if (context.getState() != LifecycleState.STARTED) {
			throw new LifecycleException(
			    "Context state is not STARTED but " + context.getStateName());
		}
	}

	private void configureTomcat() {
		tomcat.getServer()
		    .addLifecycleListener(new JreMemoryLeakPreventionListener());
		tomcat.getServer()
		    .addLifecycleListener(new ThreadLocalLeakPreventionListener());
		tomcat.getServer().addLifecycleListener(destroyListener);
	}

	private void shutdownTomcat() throws WebAppTestException {
		try {
			Callable<Boolean> contextIsDestroyed = new Callable<Boolean>() {

				@Override
				public Boolean call() throws Exception {
					return destroyListener.isDestroyed()
					        && destroyListener.isStopped();
				}

			};
			if (tomcat != null && !contextIsDestroyed.call()) {
				tomcat.stop();
				tomcat.destroy();
				await().atMost(Duration.ONE_MINUTE).until(contextIsDestroyed);
			}
		} catch (Exception e) {
			throw new WebAppTestException(e);
		} finally {
			try {
				delete(catalinaBase);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void configureContext() throws MalformedURLException {
		StandardRoot resources = new StandardRoot(context);
		resources.setCachingAllowed(false);

		context.setResources(resources);

		if (contextPath != null) {
			context.setConfigFile(contextPath.toUri().toURL());
		}

		if (context instanceof StandardContext) {
			StandardContext standardContext = (StandardContext) context;
			standardContext.setClearReferencesHttpClientKeepAliveThread(true);
			standardContext.setClearReferencesStopThreads(true);
			standardContext.setClearReferencesStopTimerThreads(true);
		}
	}

	private void ping(final URL url) throws WebAppTestException {
		try {
			await().atMost(new Duration(deployDuration, SECONDS))
			    .pollInterval(Duration.ONE_SECOND)
			    .until(new Callable<Boolean>() {

				    @Override
				    public Boolean call() throws Exception {
					    URLConnection connection = url.openConnection();
					    if (connection instanceof HttpURLConnection) {
						    HttpURLConnection httpConnection =
						            (HttpURLConnection) connection;
						    return httpConnection.getResponseCode() == 200;
					    }
					    return false;
				    }
			    });
		} catch (ConditionTimeoutException e) {
			throw new WebAppTestException(
			    "Web application not properly deployed", e);
		}
	}

	private void testLeak() throws WebAppTestException {
		if (!testLeak || classLoaderReference == null) {
			return;
		}

		Callable<Boolean> classLoaderReferenceIsNull = new Callable<Boolean>() {

			@Override
			public Boolean call() throws Exception {
				return classLoaderReference.get() == null;
			}

		};

		System.gc();

		createClassesUntil(classLoaderReferenceIsNull);

		try {
			await().atMost(Duration.TWO_MINUTES)
			    .until(classLoaderReferenceIsNull);
		} catch (ConditionTimeoutException e) {
			throw new WebAppTestException("ClassLoader not GC'ed", e);
		}
	}

	private void createClassesUntil(
	        final Callable<Boolean> classLoaderReferenceIsNull) {
		final ClassLoader classLoader = DummyClassLoader.newInstance();
		final ClassPool pool = ClassPool.getDefault();
		new Thread("classCreator") {

			@Override
			public void run() {
				try {
					while (!classLoaderReferenceIsNull.call()) {
						CtClass makeClass =
						        pool.makeClass("de.test." + UUID.randomUUID());
						makeClass.toClass(classLoader,
						    this.getClass().getProtectionDomain());
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

		}.start();
	}

	private Tomcat getTomcatInstance() throws IOException {
		catalinaBase =
		        Files.createTempDirectory("tomcat-classloader-leak-test");

		delete(catalinaBase);

		Path appBase = catalinaBase.resolve("webapps");
		Files.createDirectories(appBase);

		Tomcat tomcat = new Tomcat();
		tomcat.setPort(0);

		tomcat.setBaseDir(catalinaBase.toAbsolutePath().toString());
		tomcat.getHost().setAppBase(appBase.toAbsolutePath().toString());

		tomcat.enableNaming();

		return tomcat;
	}

	private static void delete(Path file) throws IOException {
		if (file == null || !Files.exists(file)) {
			return;
		}
		Files.walkFileTree(file, new SimpleFileVisitor<Path>() {

			@Override
			public FileVisitResult visitFile(Path file,
			        BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc)
			        throws IOException {
				Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}

		});
	}

}
