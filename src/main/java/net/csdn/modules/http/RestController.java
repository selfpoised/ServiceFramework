package net.csdn.modules.http;

import net.csdn.ServiceFramwork;
import net.csdn.annotation.filter.AroundFilter;
import net.csdn.annotation.filter.BeforeFilter;
import net.csdn.common.collect.Tuple;
import net.csdn.common.exception.ArgumentErrorException;
import net.csdn.common.exception.RecordNotFoundException;
import net.csdn.common.logging.CSLogger;
import net.csdn.common.logging.Loggers;
import net.csdn.common.path.PathTrie;
import net.csdn.common.reflect.ReflectHelper;
import net.csdn.modules.http.support.FilterHelper2;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static net.csdn.common.logging.support.MessageFormat.format;

/**
 * BlogInfo: william
 * Date: 11-9-5
 * Time: 下午4:24
 */
public class RestController {

    private CSLogger logger = Loggers.getLogger(getClass());

    private Tuple<Class<ApplicationController>, Method> defaultHandlerKey;
    private Tuple<Class<ApplicationController>, Method> errorHandlerKey;


    private final PathTrie<Tuple<Class<ApplicationController>, Method>> getHandlers = new PathTrie<Tuple<Class<ApplicationController>, Method>>();
    private final PathTrie<Tuple<Class<ApplicationController>, Method>> postHandlers = new PathTrie<Tuple<Class<ApplicationController>, Method>>();
    private final PathTrie<Tuple<Class<ApplicationController>, Method>> putHandlers = new PathTrie<Tuple<Class<ApplicationController>, Method>>();
    private final PathTrie<Tuple<Class<ApplicationController>, Method>> deleteHandlers = new PathTrie<Tuple<Class<ApplicationController>, Method>>();
    private final PathTrie<Tuple<Class<ApplicationController>, Method>> headHandlers = new PathTrie<Tuple<Class<ApplicationController>, Method>>();
    private final PathTrie<Tuple<Class<ApplicationController>, Method>> optionsHandlers = new PathTrie<Tuple<Class<ApplicationController>, Method>>();

    public void registerHandler(RestRequest.Method method, String path, Tuple<Class<ApplicationController>, Method> handler) {
        switch (method) {
            case GET:
                getHandlers.insert(path, handler);
                break;
            case DELETE:
                deleteHandlers.insert(path, handler);
                break;
            case POST:
                postHandlers.insert(path, handler);
                break;
            case PUT:
                putHandlers.insert(path, handler);
                break;
            case OPTIONS:
                optionsHandlers.insert(path, handler);
                break;
            case HEAD:
                headHandlers.insert(path, handler);
                break;
            default:
                throw new ArgumentErrorException("Can't handle [" + method + "] for path [" + path + "]");
        }
    }

    public void setDefaultHandlerKey(Tuple<Class<ApplicationController>, Method> defaultHandlerKey) {
        this.defaultHandlerKey = defaultHandlerKey;
    }

    public void setErrorHandlerKey(Tuple<Class<ApplicationController>, Method> errorHandlerKey) {
        this.errorHandlerKey = errorHandlerKey;
    }

    public Tuple<Class<ApplicationController>, Method> defaultHandlerKey() {
        return defaultHandlerKey;
    }

    public Tuple<Class<ApplicationController>, Method> errorHandlerKey() {
        return errorHandlerKey;
    }

    public void dispatchRequest(final RestRequest request, RestResponse restResponse) throws Exception {
        Tuple<Class<ApplicationController>, Method> handlerKey = getHandler(request);
        if (handlerKey == null) {
            if (defaultHandlerKey != null) {
                handlerKey = defaultHandlerKey;
            } else {
                throw new RecordNotFoundException(format("你请求的URL地址[{}]不存在", request.rawPath().toString()));
            }

        }

        // 此处ApplicationController instance 是per request的
        ApplicationController applicationController = ServiceFramwork.injector.getInstance(handlerKey.v1());
        // 让变量可访问，并赋值
        enhanceApplicationController(applicationController, request, restResponse);
        if (handlerKey == defaultHandlerKey) {
            handlerKey.v2().invoke(applicationController);
        } else {
            // 实现类似AOP功能，pre, around
            filter(handlerKey, applicationController);
        }

    }

    /**
     * 修改applicationController相应变量的访问属性为可访问，并为其赋值
     */
    public static void enhanceApplicationController(ApplicationController applicationController, final RestRequest request, RestResponse restResponse) throws Exception {
        ReflectHelper.field(applicationController, "request", request);
        ReflectHelper.field(applicationController, "restResponse", restResponse);
    }


    private void filter(Tuple<Class<ApplicationController>, Method> handlerKey, ApplicationController applicationController) throws Exception {
        Map<Method, Map<Class, List<Method>>> result = FilterHelper2.create(handlerKey.v1());
        Map<Class, List<Method>> filters = result.get(handlerKey.v2());
        WowAroundFilter first = null;

        // AOP: before
        if (filters.containsKey(BeforeFilter.class)) {
            for (Method filter : filters.get(BeforeFilter.class)) {
                filter.setAccessible(true);
                filter.invoke(applicationController);
            }
        }

        // AOP: around
        if (filters.containsKey(AroundFilter.class)) {
            Iterator<Method> iterator = filters.get(AroundFilter.class).iterator();

            WowAroundFilter wowAroundFilter = null;
            if (iterator.hasNext()) {
                Method currentFilter = iterator.next();
                wowAroundFilter = new WowAroundFilter(currentFilter, handlerKey.v2(), applicationController);
                first = wowAroundFilter;
            }
            while (iterator.hasNext()) {
                Method currentFilter = iterator.next();
                wowAroundFilter.setNext(new WowAroundFilter(currentFilter, handlerKey.v2(), applicationController));
                wowAroundFilter = wowAroundFilter.getNext();
            }
        }

        if (first != null) {
            first.invoke();
        } else {
            handlerKey.v2().invoke(applicationController);
        }

    }


    public Tuple<Class<ApplicationController>, Method> getHandler(RestRequest request) {
        String path = getPath(request);
        RestRequest.Method method = request.method();
        Map<String, String> params = new HashMap<String, String>();
        Tuple<Class<ApplicationController>, Method> result = null;
        if (method == RestRequest.Method.GET) {
            result = getHandlers.retrieve(path, params);
        } else if (method == RestRequest.Method.POST) {
            result = postHandlers.retrieve(path, params);
        } else if (method == RestRequest.Method.PUT) {
            result = putHandlers.retrieve(path, params);
        } else if (method == RestRequest.Method.DELETE) {
            result = deleteHandlers.retrieve(path, params);
        } else if (method == RestRequest.Method.HEAD) {
            result = headHandlers.retrieve(path, params);
        } else if (method == RestRequest.Method.OPTIONS) {
            result = optionsHandlers.retrieve(path, params);
        }
        mergeParams(request, params);
        return result;
    }

    /*
      queryString里的参数比url里的优先，可覆盖
     */
    public void mergeParams(RestRequest restRequest, Map<String, String> params) {
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!restRequest.params().containsKey(entry.getKey())) {
                restRequest.params().put(entry.getKey(), entry.getValue());
            }
        }
    }

    private String getPath(RestRequest request) {
        return request.rawPath();
    }


}
