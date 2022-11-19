package org.antlr.v4.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.antlr.v4.server.persistent.PersistenceLayer;
import org.antlr.v4.server.persistent.cloudstorage.CloudStoragePersistenceLayer;
import org.antlr.v4.server.unique.DummyUniqueKeyGenerator;
import org.antlr.v4.server.unique.UniqueKeyGenerator;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.LoggerFactory;


import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.antlr.v4.server.GrammarProcessor.interp;

public class ANTLRHttpServer {
	public static final String IMAGES_DIR = "/tmp/antlr-images";

	public static class ParseServlet extends DefaultServlet {
		static final ch.qos.logback.classic.Logger LOGGER =
				(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ANTLRHttpServer.class);

		@Override
		public void doPost(HttpServletRequest request, HttpServletResponse response)
			throws IOException
		{
			String json;
			try {
				response.setContentType("text/plain;charset=utf-8");
				response.setContentType("text/html;");
				response.addHeader("Access-Control-Allow-Origin", "*");

				JsonReader jsonReader = Json.createReader(request.getReader());
				JsonObject jsonObj = jsonReader.readObject();
//				System.out.println(jsonObj);

				String grammar = jsonObj.getString("grammar", "");
				String lexGrammar = jsonObj.getString("lexgrammar", ""); // can be null
				String input = jsonObj.getString("input", "");
				String startRule = jsonObj.getString("start", "");

				StringBuilder logMsg = new StringBuilder();
				logMsg.append("GRAMMAR:\n");
				logMsg.append(grammar);
				logMsg.append("\nLEX GRAMMAR:\n");
				logMsg.append(lexGrammar);
				logMsg.append("\nINPUT:\n\"\"\"");
				logMsg.append(input);
				logMsg.append("\"\"\"\n");
				logMsg.append("STARTRULE: ");
				logMsg.append(startRule);
				logMsg.append('\n');
				LOGGER.info(logMsg.toString());

				if (grammar.strip().length() == 0 && lexGrammar.strip().length() == 0) {
					json = "{\"arg_error\":\"missing either combined grammar or lexer and parser both\"}";
				}
				else if (grammar.strip().length() == 0 && lexGrammar.strip().length() > 0) {
					json = "{\"arg_error\":\"missing parser grammar\"}";
				}
				else if (startRule.strip().length() == 0) {
					json = "{\"arg_error\":\"missing start rule\"}";
				}
				else if (input.length() == 0) {
					json = "{\"arg_error\":\"missing input\"}";
				}
				else {
					try {
						json = interp(grammar, lexGrammar, input, startRule);
					}
					catch (ParseCancellationException pce) {
						json = "{\"exception_trace\":\"parser timeout ("+GrammarProcessor.MAX_PARSE_TIME_MS+"ms)\"}";
					}
					catch (Throwable e) {
						e.printStackTrace(System.err);
						json = "{}";
					}
				}
			}
			catch (Exception e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				String trace = JsonSerializer.escapeJSONString(sw.toString());
				String msg = e.getMessage();
				msg = JsonSerializer.escapeJSONString(msg);
				json = "{\"exception_trace\":\""+trace+"\",\"exception\":\""+ msg +"\"}";

			}
			LOGGER.info("RESULT:\n"+json);
			response.setStatus(HttpServletResponse.SC_OK);
			PrintWriter w = response.getWriter();
			w.write(json);
			w.flush();
		}
	}

	public static class ShareServlet extends DefaultServlet {
		static final ch.qos.logback.classic.Logger LOGGER =
				(ch.qos.logback.classic.Logger)LoggerFactory.getLogger(ANTLRHttpServer.class);

		@Override
		public void doPost(HttpServletRequest request, HttpServletResponse response)
				throws IOException
		{
			String json;
			try {
				response.setContentType("text/plain;charset=utf-8");
				response.setContentType("text/html;");
				response.addHeader("Access-Control-Allow-Origin", "*");

				JsonReader jsonReader = Json.createReader(request.getReader());
				JsonObject jsonObj = jsonReader.readObject();


				PersistenceLayer<String> persistenceLayer = new CloudStoragePersistenceLayer();
				UniqueKeyGenerator keyGen = new DummyUniqueKeyGenerator();
				Optional<String> uniqueKey = keyGen.generateKey();
				persistenceLayer.persist(jsonObj.toString().getBytes(StandardCharsets.UTF_8),
						uniqueKey.orElseThrow());

				json = "{\"resource_id\":\""+uniqueKey.orElseThrow()+"\"}";
			}
			catch (Exception e) {
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				String trace = JsonSerializer.escapeJSONString(sw.toString());
				String msg = e.getMessage();
				msg = JsonSerializer.escapeJSONString(msg);
				json = "{\"exception_trace\":\""+trace+"\",\"exception\":\""+ msg +"\"}";

			}
			LOGGER.info("RESULT:\n"+json);
			response.setStatus(HttpServletResponse.SC_OK);
			PrintWriter w = response.getWriter();
			w.write(json);
			w.flush();
		}
	}

	public static void main(String[] args) throws Exception {
		new File(IMAGES_DIR).mkdirs();

		Files.createDirectories(Path.of("/var/log/antlrlab"));
		QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setMaxThreads(10);
		threadPool.setName("server");

		Server server = new Server(threadPool);

		ServerConnector http = new ServerConnector(server);
		http.setPort(8080);

		server.addConnector(http);

//		Server server = new Server(8080);

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		context.addServlet(new ServletHolder(new ParseServlet()), "/parse/*");
		context.addServlet(new ServletHolder(new ShareServlet()), "/share/*");

		ServletHolder holderHome = new ServletHolder("static-home", DefaultServlet.class);
		holderHome.setInitParameter("resourceBase", "static");
		holderHome.setInitParameter("dirAllowed","true");
		holderHome.setInitParameter("pathInfoOnly","true");
		context.addServlet(holderHome,"/*");

		server.setHandler(context);

		server.start();
	}
}
