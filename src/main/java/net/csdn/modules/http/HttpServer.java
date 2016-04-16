package net.csdn.modules.http;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.csdn.ServiceFramwork;
import net.csdn.annotation.NoTransaction;
import net.csdn.common.collect.Tuple;
import net.csdn.common.env.Environment;
import net.csdn.common.exception.ExceptionHandler;
import net.csdn.common.logging.CSLogger;
import net.csdn.common.logging.Loggers;
import net.csdn.common.settings.Settings;
import net.csdn.constants.CError;
import net.csdn.jpa.JPA;
import net.csdn.modules.controller.API;
import net.csdn.modules.http.processor.HttpFinishProcessor;
import net.csdn.modules.http.processor.HttpStartProcessor;
import net.csdn.modules.http.processor.ProcessInfo;
import net.csdn.modules.http.processor.impl.DefaultHttpFinishProcessor;
import net.csdn.modules.http.processor.impl.DefaultHttpStartProcessor;
import net.csdn.modules.http.processor.impl.TraceHttpFinishProcessor;
import net.csdn.modules.http.processor.impl.TraceHttpStartProcessor;
import net.csdn.modules.http.support.HttpHolder;
import net.csdn.modules.log.SystemLogger;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SessionManager;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.server.session.HashSessionManager;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;


/**
 * BlogInfo: william
 * Date: 11-9-2
 * Time: 下午1:29
 */
@Singleton
public class HttpServer {
    private final Server server;
    private CSLogger logger = Loggers.getLogger(getClass());

    /**
     *
     * 实际上restController在整个ServiceFramework中是singleton
     * 详见class HttpModule line 14:
     * bind(RestController.class).toInstance(new RestController());
     *
     * RestController中存储了所有可能的用户请求url路径与其对应处理方法
     * 的映射。对应路径比如"/a/b"的处理函数，是通过树的数据结构来查找的
     *
     */
    private RestController restController;
    private boolean disableMysql = false;
    private Settings settings;
    private SystemLogger systemLogger;
    private API api;

    private List<HttpStartProcessor> httpStartProcessorList = new ArrayList();
    private List<HttpFinishProcessor> httpFinishProcessorList = new ArrayList();

    /**
     * ThreadLocal会针对每个线程独自创建线程私有变量，对其它线程不可见
     * 因而勿需同步。虽然线程亡，该变量亡，但若类似线程池复用形式，
     * 则使用完毕需显式删除，避免继承脏数据
     */
    private static ThreadLocal<HttpHolder> httpHolder = new ThreadLocal<HttpHolder>();

    public static void setHttpHolder(HttpHolder value) {
        httpHolder.set(value);
    }

    public static void removeHttpHolder() {
        httpHolder.remove();
    }

    public static HttpHolder httpHolder() {
        return httpHolder.get();
    }


    @Inject
    public HttpServer(Settings settings, SystemLogger systemLogger, RestController restController, API api) {
        this.settings = settings;
        this.systemLogger = systemLogger;
        this.restController = restController;
        this.api = api;

        // 此两处注册主要是做QPS统计
        registerHttpStartProcessor(new DefaultHttpStartProcessor());
        registerHttpFinishProcessor(new DefaultHttpFinishProcessor());

        if (settings.getAsBoolean("trace.enable", false)) {
            registerHttpStartProcessor(new TraceHttpStartProcessor());
            registerHttpFinishProcessor(new TraceHttpFinishProcessor());
        }

        Environment environment = new Environment(settings);
        disableMysql = settings.getAsBoolean(ServiceFramwork.mode + ".datasources.mysql.disable", false);
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setMinThreads(settings.getAsInt("http.threads.min", 100));
        threadPool.setMaxThreads(settings.getAsInt("http.threads.max", 1000));
        connector.setThreadPool(threadPool);
        connector.setPort(settings.getAsInt("http.port", 8080));
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();
        if (settings.getAsBoolean("application.static.enable", false)) {
            ResourceHandler resource_handler = new ResourceHandler();
            resource_handler.setDirectoriesListed(false);
            try {
                resource_handler.setBaseResource(Resource.newResource(environment.templateDirFile().getPath() + "/assets/"));
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (settings.getAsBoolean("application.session.enable", false)) {
                SessionManager sessionManager = new HashSessionManager();
                sessionManager.setSessionIdPathParameterName("none");
                handlers.setHandlers(new Handler[]{resource_handler, new SessionHandler(sessionManager), new DefaultHandler()});
            } else {
                handlers.setHandlers(new Handler[]{resource_handler, new DefaultHandler()});
            }

        } else {
            handlers.setHandlers(new Handler[]{new DefaultHandler()});
        }


        server.setHandler(handlers);
    }

    public void registerHttpStartProcessor(HttpStartProcessor httpStartProcessor) {
        httpStartProcessorList.add(httpStartProcessor);
    }

    public void registerHttpFinishProcessor(HttpFinishProcessor httpFinishProcessor) {
        httpFinishProcessorList.add(httpFinishProcessor);
    }

    class DefaultHandler extends AbstractHandler {


        private void rollback(Method action) {
            if (!disableMysql && action != null && action.getAnnotation(NoTransaction.class) == null) {
                try {
                    JPA.getJPAConfig().getJPAContext().closeTx(true);
                } catch (Exception e2) {
                    //ignore
                }
            }
        }

        private void defaultErrorAction(DefaultResponse channel, Exception e) {
            if (restController.errorHandlerKey() != null) {
                ApplicationController errorApplicationController = ServiceFramwork.injector.getInstance(restController.errorHandlerKey().v1());
                try {
                    RestController.enhanceApplicationController(errorApplicationController, HttpServer.httpHolder().restRequest(), channel);
                    try {
                        restController.errorHandlerKey().v2().invoke(errorApplicationController, e);
                    } catch (Exception e2) {
                        ExceptionHandler.renderHandle(e2);
                        channel.send();
                    }
                } catch (Exception e1) {
                    logger.error(CError.SystemProcessingError, e1);
                }
            } else {
                try {
                    channel.error(e);
                } catch (IOException e1) {
                    logger.error(CError.SystemProcessingError, e1);
                }
            }
        }


        @Override
        public void handle(String s, Request request, final HttpServletRequest httpServletRequest, final HttpServletResponse httpServletResponse) throws IOException, ServletException {

            DefaultResponse channel = new DefaultResponse(httpServletRequest, httpServletResponse, restController);
            ProcessInfo processInfo = new ProcessInfo();
            try {

                RestRequest restRequest = new DefaultRestRequest(httpServletRequest);
                HttpServer.setHttpHolder(new HttpHolder(restRequest, channel));
                Tuple<Class<ApplicationController>, Method> tuple = restController.getHandler(restRequest);
                if (tuple != null) {
                    processInfo.method = tuple.v2();
                }
                for (HttpStartProcessor httpStartProcessor : httpStartProcessorList) {
                    httpStartProcessor.process(settings, httpServletRequest, httpServletResponse, processInfo);
                }
                try {
                    // 此处最终会调用相应ApplicationController 里对应方法，而render，如果正常完成则抛出RenderFinish
                    // 下面catch里面会直接return。此时会将要返回的内容设置给相应变量，但并未写入response实体
                    restController.dispatchRequest(restRequest, channel);
                } catch (Exception e) {
                    ExceptionHandler.renderHandle(e);
                }
                // 将消息内容返回给client
                channel.send();
            } catch (Exception e) {
                if (!"qps-overflow".equals(e.getMessage())) {
                    logger.error(CError.SystemProcessingError, e);
                }
                //回滚
                rollback(processInfo.method);
                //如果有默认的action处理异常统一展示结果的话
                defaultErrorAction(channel, e);
            } finally {
                processInfo.status = channel.status();
                for (HttpFinishProcessor httpFinishProcessor : httpFinishProcessorList) {
                    httpFinishProcessor.process(settings, httpServletRequest, httpServletResponse, processInfo);
                }
                HttpServer.removeHttpHolder();
            }


        }
    }


    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server.start();
                    server.join();
                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        }).start();

    }

    public void close() {
        try {
            server.stop();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void join() {
        try {
            server.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


}
