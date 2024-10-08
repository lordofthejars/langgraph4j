package org.bsc.langgraph4j;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.bsc.langgraph4j.state.AgentState;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


/**
 * LangGraphStreamingServer is an interface that represents a server that supports streaming
 * of LangGraph.
 * Implementations of this interface can be used to create a web server
 * that exposes an API for interacting with compiled language graphs.
     */
public interface LangGraphStreamingServer {

    Logger log = LoggerFactory.getLogger(LangGraphStreamingServer.class);

    CompletableFuture<Void> start() throws Exception;

    static Builder builder() {
        return new Builder();
    }

    class Builder {
        private int port = 8080;
        private Map<String, ArgumentMetadata> inputArgs = new HashMap<>();
        private String title = null;
        private ObjectMapper objectMapper;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder addInputStringArg(String name, boolean required) {
            inputArgs.put(name, new ArgumentMetadata("string", required));
            return this;
        }

        public Builder addInputStringArg(String name) {
            inputArgs.put(name, new ArgumentMetadata("string", true));
            return this;
        }

        public <State extends AgentState> LangGraphStreamingServer build(CompiledGraph<State> compiledGraph) throws Exception {

            Server server = new Server();

            ServerConnector connector = new ServerConnector(server);
            connector.setPort(port);
            server.addConnector(connector);

            ResourceHandler resourceHandler = new ResourceHandler();

//            Path publicResourcesPath = Paths.get("jetty", "src", "main", "webapp");
//            Resource baseResource = ResourceFactory.of(resourceHandler).newResource(publicResourcesPath));
            Resource baseResource = ResourceFactory.of(resourceHandler).newClassLoaderResource("webapp");
            resourceHandler.setBaseResource(baseResource);

            resourceHandler.setDirAllowed(true);

            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);

            if (objectMapper == null) {
                objectMapper = new ObjectMapper();
            }
            // context.setContextPath("/");
            // Add the streaming servlet
            context.addServlet(new ServletHolder(new GraphExecutionServlet<State>(compiledGraph, objectMapper)), "/stream");

            InitData initData = new InitData(title, inputArgs);
            context.addServlet(new ServletHolder(new GraphInitServlet<State>(compiledGraph, initData)), "/init");

            Handler.Sequence handlerList = new Handler.Sequence(resourceHandler, context);

            server.setHandler(handlerList);

            return new LangGraphStreamingServer() {
                @Override
                public CompletableFuture<Void> start() throws Exception {
                    return CompletableFuture.runAsync(() -> {
                        try {
                            server.start();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, Runnable::run);

                }
            };

        }
    }
}


class GraphExecutionServlet<State extends AgentState> extends HttpServlet {
    final CompiledGraph<State> compiledGraph;
    final ObjectMapper objectMapper;

    public GraphExecutionServlet(CompiledGraph<State> compiledGraph, ObjectMapper objectMapper) {
        Objects.requireNonNull(compiledGraph, "compiledGraph cannot be null");
        this.compiledGraph = compiledGraph;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setHeader("Accept", "application/json");
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");

        final PrintWriter writer = response.getWriter();

        Map<String, Object> dataMap = objectMapper.readValue(request.getInputStream(), new TypeReference<Map<String, Object>>() {
        });

        // Start asynchronous processing
        var asyncContext = request.startAsync();

        try {
            compiledGraph.stream(dataMap)
                    .forEachAsync(s -> {
                        try {

                            writer.print("{");
                            writer.printf("\"node\": \"%s\"", s.node());
                            try {
                                var stateAsString = objectMapper.writeValueAsString(s.state().data());
                                writer.printf(",\"state\": %s", stateAsString);
                            } catch (IOException e) {
                                LangGraphStreamingServer.log.info("error serializing state", e);
                                writer.printf(",\"state\": {}");
                            }
                            writer.print("}");
                            writer.flush();
                            TimeUnit.SECONDS.sleep(1);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                    })
                    .thenAccept(v -> writer.close())
                    .thenAccept(v -> asyncContext.complete())
            ;

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

record ArgumentMetadata(
        String type,
        boolean required) {
}

record InitData(
        String title,
        Map<String, ArgumentMetadata> args) {
}

/**
 * return the graph representation in mermaid format
 */
class GraphInitServlet<State extends AgentState> extends HttpServlet {

    final CompiledGraph<State> compiledGraph;
    final ObjectMapper objectMapper = new ObjectMapper();
    final InitData initData;

    record Result(
            String graph,
            String title,
            Map<String, ArgumentMetadata> args
    ) {

        public Result(GraphRepresentation graph, InitData initData) {
            this(graph.getContent(), initData.title(), initData.args()); // graph.getContent();
        }
    }

    public GraphInitServlet(CompiledGraph<State> compiledGraph, InitData initData) {
        Objects.requireNonNull(compiledGraph, "compiledGraph cannot be null");
        this.compiledGraph = compiledGraph;
        this.initData = initData;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        GraphRepresentation graph = compiledGraph.getGraph(GraphRepresentation.Type.MERMAID, initData.title(), false);

        final Result result = new Result(graph, initData);
        String resultJson = objectMapper.writeValueAsString(result);
        // Start asynchronous processing
        final PrintWriter writer = response.getWriter();
        writer.println(resultJson);
        writer.close();
    }
}
