package com.github.jsocle

import com.github.jsocle.client.TestClient
import com.github.jsocle.requests.Request
import com.github.jsocle.requests.RequestHandlerMatchResult
import com.github.jsocle.requests.RequestImpl
import com.github.jsocle.requests.session.Session
import com.github.jsocle.requests.session.StringSession
import com.github.jsocle.response.Response
import com.github.jsocle.server.JettyServer
import com.github.jsocle.servlet.JSocleHttpServlet
import org.eclipse.jetty.servlet.DefaultServlet
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import kotlin.properties.Delegates

public open class JSocle(config: JSocleConfig? = null, staticPath: Path? = null) : JSocleApp() {
    public var server: JettyServer by Delegates.notNull()
        private set
    public val client: TestClient
        get() = TestClient(this)

    public val config: JSocleConfig = config ?: JSocleConfig()

    private val servlet = JSocleHttpServlet(this)
    private val rootPath = Paths.get(".").toAbsolutePath()
    private val staticPath = staticPath ?: rootPath.resolve("static")
    private val hooks = Hooks()

    @JvmOverloads
    public fun run(port: Int = 8080, withIn: JSocle.() -> Unit = { server.join() }) {
        processOnBeforeFirstRequest()

        server = JettyServer(port)
        val servletContextHandler = ServletContextHandler()
        servletContextHandler.contextPath = "/"
        servletContextHandler.resourceBase = staticPath.parent.toString()
        servletContextHandler.addServlet(ServletHolder(DefaultServlet()), "/static/*")
        servletContextHandler.addServlet(ServletHolder(servlet), "/*")

        server.handler = servletContextHandler
        server.start()
        try {
            withIn()
        } finally {
            if (server.isRunning) {
                server.stop()
            }
        }
    }

    fun requestContext(req: HttpServletRequest, method: Request.Method, body: (request: RequestImpl?, result: RequestHandlerMatchResult?) -> Unit) {
        val requestUri = URLDecoder.decode(req.requestURI, "UTF-8")
        val result = findRequestHandler(requestUri)
        if (result == null) {
            body(null, null)
            return
        }
        val request = RequestImpl(requestUri, result.pathVariables, req, method, this)
        com.github.jsocle.request.push(request)
        try {
            body(request, result)
        } finally {
            com.github.jsocle.request.pop()
        }
    }

    fun processRequest(req: HttpServletRequest, resp: HttpServletResponse, method: Request.Method) {
        processOnBeforeFirstRequest()

        requestContext(req, method) { request, result ->
            if (request == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND)
                return@requestContext
            }

            val ret = result!!.handler.handle(request)
            val response = if (ret !is Response) makeResponse(ret) else ret
            resp.addCookie(Cookie("session", request.session.serialize()));
            response.headers.forEach { entry ->
                entry.value.forEach { resp.addHeader(entry.key, it) }
            }
            resp.writer.use {
                response(it)
            }
        }
    }

    private fun processOnBeforeFirstRequest() {
        val onBeforeFirstRequest = hooks.onBeforeFirstRequestCallbacks ?: return
        hooks.onBeforeFirstRequestCallbacks = null
        onBeforeFirstRequest.forEach { it() }
        onBeforeFirstRequest()
    }

    public fun buildSession(cookie: String?): Session {
        return StringSession(cookie, config)
    }

    companion object {
        init {
            com.github.jsocle.form.request.parameters = { request.servlet.parameterMap }
        }
    }

    open fun onBeforeFirstRequest() {
    }

    fun addOnBeforeFirstRequest(callback: () -> Unit) {
        hooks.onBeforeFirstRequestCallbacks!!.add(callback)
    }
}