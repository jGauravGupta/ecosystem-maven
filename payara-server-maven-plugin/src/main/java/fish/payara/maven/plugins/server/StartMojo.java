/*
 *
 * Copyright (c) 2025 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.maven.plugins.server;

import fish.payara.tools.ai.MarkdownToCmdHighlighter;
import fish.payara.tools.ai.PayaraAIAgent;
import fish.payara.maven.plugins.server.manager.PayaraServerRemoteInstance;
import fish.payara.maven.plugins.server.manager.RemoteInstanceManager;
import fish.payara.maven.plugins.server.manager.PayaraServerLocalInstance;
import fish.payara.maven.plugins.server.manager.LocalInstanceManager;
import fish.payara.maven.plugins.server.manager.InstanceManager;
import fish.payara.maven.plugins.AutoDeployHandler;
import fish.payara.maven.plugins.LogUtils;
import fish.payara.maven.plugins.PropertiesUtils;
import fish.payara.maven.plugins.StartTask;
import fish.payara.maven.plugins.WebDriverFactory;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.twdata.maven.mojoexecutor.MojoExecutor;

import java.io.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static fish.payara.maven.plugins.server.Configuration.*;
import fish.payara.maven.plugins.server.manager.PayaraServerInstance;
import static fish.payara.maven.plugins.server.manager.PayaraServerLocalInstance.HTTP;
import fish.payara.maven.plugins.server.response.JsonResponse;
import fish.payara.maven.plugins.server.response.Response;
import fish.payara.maven.plugins.server.utils.TempDirectoryResolver;
import fish.payara.tools.ai.JMXFetchSpecificMBean;
import fish.payara.tools.ai.lang.PreferencesManager;
import java.awt.Desktop;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.project.MavenProject;
import org.json.JSONException;
import org.json.JSONObject;
import org.openqa.selenium.WebDriver;

/**
 * Run mojo that executes payara-server
 *
 * @author Gaurav Gupta
 */
@Mojo(name = "start")
public class StartMojo extends ServerMojo implements StartTask {

    private static final String ERROR_MESSAGE = "Errors occurred while executing payara-server.";
    private static final String REMOTE_INSTANCE_NOT_RUNNING_MESSAGE = "The remote Payara server instance is not running.";

    /**
     * Runs Payara server as a daemon (background process).
     */
    @Parameter(property = "payara.daemon", defaultValue = "${env.PAYARA_DAEMON}")
    private boolean daemon;

    /**
     * If set to true, exits the Maven process immediately after starting the server.
     */
    @Parameter(property = "payara.immediate.exit", defaultValue = "${env.PAYARA_IMMEDIATE_EXIT}")
    private boolean immediateExit;

    /**
     * Enables automatic deployment of the application.
     */
    @Parameter(property = "payara.auto.deploy", defaultValue = "${env.PAYARA_AUTO_DEPLOY}")
    protected Boolean autoDeploy;

    /**
     * Keeps the current state of the Payara server on restart.
     */
    @Parameter(property = "payara.keep.state", defaultValue = "${env.PAYARA_KEEP_STATE}")
    protected Boolean keepState;

    /**
     * Enables live reload for automatic updates.
     */
    @Parameter(property = "payara.live.reload", defaultValue = "${env.PAYARA_LIVE_RELOAD}")
    protected Boolean liveReload;

    /**
     * Specifies the browser to open after deployment.
     */
    @Parameter(property = "payara.browser", defaultValue = "${env.PAYARA_BROWSER}")
    protected String browser;

    /**
     * Trims excessive logs for a cleaner output.
     */
    @Parameter(property = "payara.trim.log", defaultValue = "${env.PAYARA_TRIM_LOG}")
    protected Boolean trimLog;

    /**
     * Enables hot deployment of application changes.
     */
    @Parameter(property = "payara.hot.deploy", defaultValue = "${env.PAYARA_HOT_DEPLOY}")
    protected boolean hotDeploy;

    /**
     * The directory where the web application is built.
     * Default value points to the exploded directory.
     */
    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}", required = true)
    protected File webappDirectory;

    /**
     * Enables debugging mode.
     * If set to "true", the server waits for a debugger to attach on the debug port.
     */
    @Parameter(property = "payara.debug", defaultValue = "${env.PAYARA_DEBUG}")
    protected String debug;

    /**
     * The port for remote debugging.
     */
    @Parameter(property = "payara.debug.port", defaultValue = "${env.PAYARA_DEBUG_PORT}")
    protected String debugPort;
    
    @Parameter(property = "payara.ai.agent", defaultValue = "${env.PAYARA_AI_AGENT}")
    protected Boolean aiAgent;

    /**
     * Additional command-line options for Payara server.
     */
    @Parameter
    private List<Option> commandLineOptions;
    
    @Parameter(property = "payara.commandLineOptions", defaultValue = "${env.PAYARA_COMMANDLINE_OPTIONS}")
    private List<String> commandLineOptionsString;

    /**
     * Additional Java command-line options for the JVM.
     */
    @Parameter
    private List<Option> javaCommandLineOptions;
    
    @Parameter(property = "payara.javaCommandLineOptions", defaultValue = "${env.PAYARA_JAVA_COMMANDLINE_OPTIONS}")
    private List<String> javaCommandLineOptionsString;

    /**
     * Socket connection timeout (in miliseconds).
     */
    @Parameter(property = "payara.http.connection.timeout", defaultValue = "${env.PAYARA_HTTP_CONNECTION_TIMEOUT}")
    public Integer httpConnectionTimeout;

    /**
     * Socket read timeout (in miliseconds).
     */
    @Parameter(property = "payara.http.read.timeout", defaultValue = "${env.PAYARA_HTTP_READ_TIMEOUT}")
    public Integer httpReadTimeout;

    private Process serverProcess;
    private Thread serverProcessorThread;
    private final ThreadGroup threadGroup;
    private AutoDeployHandler autoDeployHandler;
    private WebDriver driver;
    private String applicationURL;
    private InstanceManager serverManager;
    private String appPath, projectName;
    private PayaraServerInstance instance;
    private PayaraAIAgent payaraAIAgent;
    private boolean monitoringEnabled;

    StartMojo() {
        threadGroup = new ThreadGroup(SERVER_THREAD_NAME);
        if (debug == null || debug.isEmpty()) {
            debug = "false";
        }
        if(payaraServerVersion == null) {
            payaraServerVersion = "6.2025.6";
        }
        if (httpConnectionTimeout == null) {
            httpConnectionTimeout = 3000;
        }
        if (httpReadTimeout == null) {
            httpReadTimeout = 3000;
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (trimLog == null) {
            trimLog = false;
        }
        if (autoDeploy == null) {
            autoDeploy = false;
        }
        if (liveReload == null) {
            liveReload = false;
        }
        if (keepState == null) {
            keepState = false;
        }
        if (aiAgent == null) {
            aiAgent = false;
        }
        if (autoDeploy && autoDeployHandler == null) {
            autoDeployHandler = new ServerAutoDeployHandler(this, webappDirectory);
            Thread devModeThread = new Thread(autoDeployHandler);
            devModeThread.setDaemon(true);
            devModeThread.start();
        } else {
            autoDeployHandler = null;
        }
        
        PreferencesManager pm = PreferencesManager.getInstance();
        if (aiAgent && (pm.getApiKey() != null || pm.getProviderLocation() != null)) {
            CompletableFuture.runAsync(() -> {
                try {
                    payaraAIAgent = new PayaraAIAgent();
                } catch (Exception ex) {
                    getLog().error(ex);
                }
            });
        }

        if (skip) {
            getLog().info("Start mojo execution is skipped");
            return;
        }
        
        if (commandLineOptionsString != null && !commandLineOptionsString.isEmpty()) {
            if (commandLineOptions == null) {
                commandLineOptions = new ArrayList<>();
            }
            for (String optStr : commandLineOptionsString) {
                // Assume each string is name=value or just name
                Option option = new Option();
                if (optStr.contains("=")) {
                    String[] parts = optStr.split("=", 2);
                    option.setKey(parts[0]);
                    option.setValue(parts[1]);
                } else {
                    option.setKey(optStr);
                    option.setValue(null);
                }
                commandLineOptions.add(option);
            }
        }

        if (javaCommandLineOptionsString != null && !javaCommandLineOptionsString.isEmpty()) {
            if (javaCommandLineOptions == null) {
                javaCommandLineOptions = new ArrayList<>();
            }
            for (String optStr : javaCommandLineOptionsString) {
                // Assume each string is name=value or just name
                Option option = new Option();
                if (optStr.contains("=")) {
                    String[] parts = optStr.split("=", 2);
                    option.setKey(parts[0]);
                    option.setValue(parts[1]);
                } else {
                    option.setKey(optStr);
                    option.setValue(null);
                }
                javaCommandLineOptions.add(option);
            }
        }

        serverProcessorThread = new Thread(threadGroup, () -> {
            if (remote) {
                instance = new PayaraServerRemoteInstance(hostName);
                instance.setAdminUser(adminUser);
                instance.setAdminPassword(getAdminPassword());
                if (adminPort != null) {
                    instance.setAdminPort(Integer.parseInt(adminPort));
                }
                if (httpPort != null) {
                    instance.setHttpPort(Integer.parseInt(httpPort));
                }
                if (httpsPort != null) {
                    instance.setHttpsPort(Integer.parseInt(httpsPort));
                }
                instance.setHttpConnectionTimeout(httpConnectionTimeout);
                instance.setHttpReadTimeout(httpReadTimeout);
                if (protocol != null) {
                    instance.setProtocol(protocol);
                }
                serverManager = new RemoteInstanceManager((PayaraServerRemoteInstance) instance, getLog());
                if (serverManager.isServerAlreadyRunning()) {
                    Thread logThread = streamRemoteServerLog();
                    appPath = evaluateProjectArtifactAbsolutePath("." + mavenProject.getPackaging());
                    projectName = mavenProject.getName().replaceAll("\\s+", "");
                    deployApplication();
                    openApp();
                    try {
                        logThread.join();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    throw new RuntimeException(REMOTE_INSTANCE_NOT_RUNNING_MESSAGE);
                }
            } else {
                try {
                    final String path = decideOnWhichServerToUse();
                    instance = new PayaraServerLocalInstance(javaHome, path, domainName);
                    instance.setAdminUser(adminUser);
                    instance.setAdminPassword(getAdminPassword());
                    if (adminPort != null) {
                        instance.setAdminPort(Integer.parseInt(adminPort));
                    }
                    if (httpPort != null) {
                        instance.setHttpPort(Integer.parseInt(httpPort));
                    }
                    if (httpsPort != null) {
                        instance.setHttpsPort(Integer.parseInt(httpsPort));
                    }
                    instance.setHttpConnectionTimeout(httpConnectionTimeout);
                    instance.setHttpReadTimeout(httpReadTimeout);
                    if (protocol != null) {
                        instance.setProtocol(protocol);
                    }
                    serverManager = new LocalInstanceManager((PayaraServerLocalInstance) instance, getLog());
                    if (!serverManager.isServerAlreadyRunning()) {
                        ProcessBuilder processBuilder = ((LocalInstanceManager) serverManager).startServer(debug, debugPort, javaCommandLineOptions, commandLineOptions);
                        getLog().info("Starting Payara Server [" + path + "] with the these arguments: " + processBuilder.command());
                        serverProcess = processBuilder.start();

                        if (daemon) {
                            redirectStream(serverProcess.getInputStream(), System.out);
                            redirectStream(serverProcess.getErrorStream(), System.err);
                        } else {
                            redirectStreamToGivenOutputStream(serverProcess.getInputStream(), System.out);
                            redirectStreamToGivenOutputStream(serverProcess.getErrorStream(), System.err);
                        }
                        serverManager.connectWithServer();
                    } else {
                        streamLocalServerLog((PayaraServerLocalInstance) instance);
                    }
                    if (exploded) {
                        appPath = evaluateProjectArtifactAbsolutePath("");
                    } else {
                        appPath = evaluateProjectArtifactAbsolutePath("." + mavenProject.getPackaging());
                    }
                    projectName = mavenProject.getName().replaceAll("\\s+", "");
                    deployApplication();
                    openApp();
                    watchAsadminCommand();
                    int exitCode = serverProcess.waitFor();
                    if (exitCode != 0) { // && !autoDeploy
                        throw new MojoFailureException(ERROR_MESSAGE);
                    }
                } catch (InterruptedException ignored) {
                } catch (Exception e) {
                    throw new RuntimeException(ERROR_MESSAGE, e);
                } finally {
                    if (!daemon) {
                        closeServerProcess();
                    }
                }
            }
        });

        if (daemon) {
            serverProcessorThread.setDaemon(true);
            serverProcessorThread.start();

            if (!immediateExit) {
                try {
                    serverProcessorThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } else {
            Runtime.getRuntime().addShutdownHook(killServerProcess());
            serverProcessorThread.run();

            if (autoDeploy) {
                while (autoDeployHandler.isAlive()) {
                    serverProcessorThread.run();
                }
            }
        }
    }

    public void deployApplication() {
        serverManager.undeployApplication(projectName, instanceName);
        URI appUri = serverManager.deployApplication(projectName, appPath, instanceName, contextRoot, exploded, hotDeploy);
        if (appUri != null) {
            applicationURL = appUri.toString();
        }
    }

    private Thread killServerProcess() {
        return new Thread(threadGroup, () -> {
            if (serverProcess != null && serverProcess.isAlive()) {
                try {
                    serverManager.undeployApplication(projectName, instanceName);
                    serverProcess.destroy();
                    serverProcess.waitFor(15, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                } finally {
                    serverProcess.destroyForcibly();
                }
            }
            if (autoDeployHandler != null) {
                autoDeployHandler.stop();
            }
            if (driver != null) {
                try {
                    PropertiesUtils.saveProperties(applicationURL, driver.getCurrentUrl());
                } catch (Throwable t) {
                    getLog().debug(t);
                } finally {
                    try {
                        driver.quit();
                    } catch (Throwable t) {
                        getLog().debug(t);
                    }
                }
            }
        });
    }

    private String decideOnWhichServerToUse() throws MojoExecutionException {
        if (payaraServerPath != null) {
            return payaraServerPath;
        }

        if (artifactItem != null 
                && artifactItem.getGroupId() != null
                && artifactItem.getArtifactId() != null) {
            DefaultArtifact artifact = new DefaultArtifact(artifactItem.getGroupId(),
                    artifactItem.getArtifactId(),
                    artifactItem.getVersion(),
                    null,
                    artifactItem.getType(),
                    artifactItem.getClassifier(),
                    new DefaultArtifactHandler("zip"));
            
            File targetDir = TempDirectoryResolver.resolvePayaraTempDir(payaraServerVersion);
            // Check if the target directory already exists
            File extractedDir = new File(targetDir + File.separator + "payara" + artifactItem.getVersion().charAt(0));
            if (!extractedDir.exists()) {
                try {
                    getLog().info("Extracting Payara Server to " + targetDir);
                    extractZipFile(findLocalPathOfArtifact(artifact), targetDir);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to extract Payara Server zip file", e);
                }
            }

            return extractedDir.getAbsolutePath();
        }

        if (payaraServerVersion != null) {
            MojoExecutor.ExecutionEnvironment environment = getEnvironment();
            ServerFetchProcessor serverFetchProcessor = new ServerFetchProcessor();
            serverFetchProcessor.set(payaraServerVersion).handle(environment);

            // after downloading extract the zip
            DefaultArtifact artifact = new DefaultArtifact(SERVER_GROUPID, SERVER_ARTIFACTID,
                    payaraServerVersion,
                    null,
                    "zip",
                    null,
                    new DefaultArtifactHandler("zip"));
            File tmpDir = new File(System.getProperty("java.io.tmpdir"));
            File targetDir = new File(tmpDir, "payara-server-" + payaraServerVersion);
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            // Check if the target directory already exists
            File extractedDir = new File(targetDir + File.separator + "payara" + payaraServerVersion.charAt(0));
            if (!extractedDir.exists()) {
                try {
                    getLog().info("Extracting the Payara Server to " + targetDir);
                    extractZipFile(findLocalPathOfArtifact(artifact), targetDir);
                } catch (IOException e) {
                    throw new MojoExecutionException("Failed to extract Payara Server zip file", e);
                }
            }

            return extractedDir.getAbsolutePath();
        }

        throw new MojoExecutionException("Could not determine Payara Server path. Please set it by defining either \"payaraServerPath\" or \"artifactItem\" configuration options.");
    }

    private String findLocalPathOfArtifact(DefaultArtifact artifact) {
        Artifact payaraServerArtifact = mavenSession.getLocalRepository().find(artifact);
        return payaraServerArtifact.getFile().getAbsolutePath();
    }

    private void extractZipFile(String zipFilePath, File destDir) throws IOException {
        if (!destDir.exists()) {
            destDir.mkdirs(); // Create destination directory if it doesn't exist
        }
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                File dirEntry = new File(destDir, entry.getName());
                if (!entry.isDirectory()) {
                    // If the entry is a file, extract it
                    extractFile(zipIn, dirEntry);
                } else {
                    // If the entry is a directory, create it
                    dirEntry.mkdirs();
                }
                zipIn.closeEntry();
            }
        }
    }

    private void extractFile(ZipInputStream zipIn, File filePath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = zipIn.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
    }

    protected String getBaseDir() {
        return mavenProject.getBuild().getDirectory();
    }

    private String evaluateProjectArtifactAbsolutePath(String extension) {
        String projectJarAbsolutePath = getBaseDir() + File.separator;
        projectJarAbsolutePath += evaluateExecutorName(extension);
        return projectJarAbsolutePath;
    }

    private String evaluateExecutorName(String extension) {
        if (StringUtils.isNotEmpty(mavenProject.getBuild().getFinalName())) {
            return mavenProject.getBuild().getFinalName() + extension;
        }
        return mavenProject.getArtifactId() + '-' + mavenProject.getVersion() + extension;
    }

    void closeServerProcess() {
        if (serverProcess != null) {
            try {
                serverProcess.exitValue();
            } catch (IllegalThreadStateException e) {
                serverProcess.destroy();
                getLog().info("Terminated payara-server.");
            }
        }
    }

    Process getServerProcess() {
        return this.serverProcess;
    }

    private void watchAsadminCommand() {
        Thread thread = new Thread(threadGroup, () -> {
            try (Scanner scanner = new Scanner(System.in)) {
                String userQuery = null;

                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    Thread.currentThread().interrupt();
                }));

                while (!Thread.currentThread().isInterrupted() && !"exit".equals(userQuery)) {
                    try {
                        if (scanner.hasNextLine()) {
                            userQuery = scanner.nextLine();
                        } else {
                            Thread.currentThread().interrupt();
                            break; // Exit if input stream closes
                        }
                    } catch (java.util.NoSuchElementException nsee) {
                        break;
                    }
                    try {
                        if (userQuery.startsWith("asadmin")) {
                            if (serverManager instanceof LocalInstanceManager) {
                                String repsonse = ((LocalInstanceManager) serverManager).runAsadminCommand(userQuery.substring(8));
                                getLog().info(repsonse);
                            }
                        } else if (userQuery.equals("deploy")) {
                            deployApplication();
                        } else if (userQuery.equals("undeploy")) {
                            serverManager.undeployApplication(projectName, instanceName);
                        } else if (userQuery.equals("exit")) {
                            Thread.currentThread().interrupt();
                            getLog().info("watchAsadminCommand exit");
                            killServerProcess().start();
                            break;
                        } else if (!userQuery.trim().isEmpty()) {
                            if(payaraAIAgent == null) {
                                getLog().error("Payara AI Agent not initialized.");
                            }
                            String response = payaraAIAgent.query(userQuery, projectName).trim();
                            if (payaraAIAgent.isAsAdminCommand(response)) {
                                getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(response));
                                if (payaraAIAgent.isReadonlyCommand(response)) {
                                    if (serverManager instanceof LocalInstanceManager) {
                                        String cmdresponse = ((LocalInstanceManager) serverManager).runAsadminCommand(payaraAIAgent.getAsAdminCommand(response));
                                        String finalRes = payaraAIAgent.processAsadmin(userQuery, response + " \n" + cmdresponse);
                                        getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(finalRes));
                                    }
                                }
                            } else if (payaraAIAgent.isRestEndpoint(response)) {
                                enableMonitoring();
                                callEndpoint(userQuery, response);
                            } else if (payaraAIAgent.isJmxMbean(response)) {
                                StringBuilder sb = new StringBuilder();
                                for (String mbean : payaraAIAgent.getJmxMbean(response)) {
                                    sb.append(mbean).append('\n');
                                    String res = JMXFetchSpecificMBean.execute(mbean);
                                    sb.append(res).append("\n=================\n");
                                }
                                String finalRes = payaraAIAgent.processJmxMbeansData(userQuery, sb.toString());
                                getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(finalRes));
                            } else if (payaraAIAgent.isServerLog(response)
                                    && instance instanceof PayaraServerLocalInstance) {
                                String finalRes = payaraAIAgent.processServerLogData(userQuery, ((PayaraServerLocalInstance)instance).readServerLog());
                                getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(finalRes));
                            } else if (payaraAIAgent.isDomainXml(response)) {
                                String finalRes = payaraAIAgent.processServerLogData(userQuery, ((PayaraServerLocalInstance)instance).readDomainXml());
                                getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(finalRes));
                            } else {
                                getLog().info(response);
                            }
                        }
                    } catch (Exception ex) {
                        Logger.getLogger(StartMojo.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        });

        thread.setDaemon(false);
        thread.start();
    }

    private void callEndpoint(String userQuery, String response) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String endpoint : payaraAIAgent.getRestEndpoint(response)) {
            endpoint = endpoint.replace("${appname}", projectName);
            sb.append(endpoint).append('\n');
            Response res = serverManager.runEndpoint(endpoint);
            if (res != null) {
                String endpointResponse = ((JsonResponse) res).getJsonBody().getJSONObject("extraProperties").toString();
                sb.append(endpointResponse).append("\n=================\n");
            }
        }
        String finalRes = payaraAIAgent.processMonitoringData(userQuery, sb.toString());
        callChildEndpoint(userQuery, finalRes);
    }
    
    private void callChildEndpoint(String userQuery, String response) throws IOException {
        try {
            if (response.startsWith("```json")) {
                response = response.substring(7, response.length() - 3);
            } else if (response.startsWith("```")) {
                response = response.substring(3, response.length() - 3);
            }
            StringBuilder sb = new StringBuilder();
            JSONObject jsonObject = new JSONObject(response);
            if (jsonObject.has("response")) {
                getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(jsonObject.getString("response")));
            }
            if (jsonObject.has("childResource")
                    && jsonObject.getJSONArray("childResource").length() > 0) {
                for (int i = 0; i < jsonObject.getJSONArray("childResource").length(); i++) {
                    String endpoint = jsonObject.getJSONArray("childResource").getString(i);
                    endpoint = endpoint.replace("${appname}", projectName);
                    sb.append(endpoint).append('\n');
                    Response res = serverManager.runEndpoint(endpoint);
                    if (res != null) {
                        String endpointResponse = ((JsonResponse) res).getJsonBody().getJSONObject("extraProperties").toString();
                        sb.append(endpointResponse).append("\n=================\n");
                    }
                }
                String finalRes = payaraAIAgent.processMonitoringData(userQuery, sb.toString());
                callChildEndpoint(userQuery, finalRes);
            }
        } catch (JSONException e) {
            getLog().info(MarkdownToCmdHighlighter.convertMdToAnsi(response));
        }
    }
    private void enableMonitoring() throws Exception {
        if (!monitoringEnabled) {
            List<String> enableMonitoring = new ArrayList<>(List.of(
                    "set-monitoring-level --level=HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH:HIGH --module=jvm:transactionService:connectorService:jmsService:security:webContainer:jersey:webServicesContainer:jpa:jdbcConnectionPool:threadPool:ejbContainer:orb:connectorConnectionPool:deployment:httpService --target=server-config",
                    "set-monitoring-service-configuration --mbeanEnabled=true --monitoringEnabled=true --dtraceEnabled=false --target=server-config"
            ));
            for (String command : enableMonitoring) {
                String response = ((LocalInstanceManager) serverManager).runAsadminCommand(command);
                getLog().info(response);
            }
            monitoringEnabled = true;
        }
    }

    private Thread streamRemoteServerLog() {
        final Thread thread = new Thread(threadGroup, () -> {
            try {
                while (true) {
                    String log = ((RemoteInstanceManager) serverManager).fetchLogs(instanceName);
                    if (log != null && !log.isEmpty()) {
                        System.out.println(log);
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(false);
        thread.start();
        return thread;
    }

    private void streamLocalServerLog(PayaraServerLocalInstance instance) {
        final Thread thread = new Thread(threadGroup, () -> {
            File logFile = new File(instance.getServerLog());
            if (logFile.exists()) {
                try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                    raf.seek(raf.length());
                    String line;
                    while (true) {
                        while ((line = raf.readLine()) != null) {
                            System.out.println(line);
                        }
                        Thread.sleep(1000);
                    }
                } catch (IOException e) {
                    getLog().error("Error occurred while streaming server.log", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                getLog().warn("Log file does not exist: " + logFile.getAbsolutePath());
            }
        });
        thread.setDaemon(false);
        thread.start();
    }

    private void redirectStream(final InputStream inputStream, final PrintStream printStream) {
        final Thread thread = new Thread(threadGroup, () -> {
            BufferedReader br;
            StringBuilder sb = new StringBuilder();

            String line;
            try {
                br = new BufferedReader(new InputStreamReader(inputStream));
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                    printStream.println(line);
                    if (!immediateExit && sb.toString().contains(SERVER_READY_MESSAGE)) {
                        serverProcessorThread.interrupt();
                        br.close();
                        break;
                    }
                }
            } catch (IOException e) {
                getLog().error(ERROR_MESSAGE, e);
            }
        });
        thread.setDaemon(false);
        thread.start();
    }

    private void redirectStreamToGivenOutputStream(final InputStream inputStream, final OutputStream outputStream) {
        Thread thread = new Thread(threadGroup, () -> {
            try {
                if (liveReload && outputStream instanceof PrintStream) {
                    String line;
                    BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                    PrintStream printStream = (PrintStream) outputStream;

                    while ((line = br.readLine()) != null) {
                        printStream.println(trimLog ? LogUtils.trimLog(line) : line);
                        if (line.contains(APP_DEPLOYMENT_FAILED)) {
                            WebDriverFactory.updateTitle(APP_DEPLOYMENT_FAILED_MESSAGE, getEnvironment().getMavenProject(), driver, this.getLog());
                        } else if (applicationURL != null
                                && !applicationURL.isEmpty()
                                && driver != null
                                && line.contains(APP_DEPLOYED)) {
                            try {
                                driver.navigate().refresh();
                            } catch (Exception ex) {
                                getLog().debug("Error in refreshing with WebDriver", ex);
                            }
                        } else if (autoDeploy
                                && line.contains(INOTIFY_USER_LIMIT_REACHED_MESSAGE)) {
                            getLog().error(WATCH_SERVICE_ERROR_MESSAGE);
                        }
                    }
                } else {
                    IOUtils.copy(inputStream, outputStream);
                }
            } catch (IOException e) {
                getLog().error("Error occurred while reading stream", e);
            }
        });
        thread.setDaemon(false);
        thread.start();
    }

    private void openApp() {
        try {
            driver = WebDriverFactory.createWebDriver(browser, getLog());
            String url = PropertiesUtils.getProperty(applicationURL, applicationURL);
            if ((url == null || url.isEmpty())) {
                url = instance.getProtocol() + "://" + instance.getHost() + ":" + (instance.getProtocol().equals(HTTP) ? instance.getHttpPort() : instance.getHttpsPort());
                if (contextRoot != null) {
                    url = url + "/" + contextRoot;
                }
                applicationURL = url;
            }
            driver.get(url);
        } catch (Exception ex) {
            getLog().error("Error in running WebDriver", ex);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(applicationURL));
                }
            } catch (IOException | URISyntaxException e) {
                getLog().error("Error in running Desktop browse", e);
            } finally {
                driver = null;
            }
        }
    }

    @Override
    public WebDriver getDriver() {
        return driver;
    }

    @Override
    public MavenProject getProject() {
        return this.getEnvironment().getMavenProject();
    }

    @Override
    public List<String> getRebootOnChange() {
        return Collections.EMPTY_LIST;
    }

    @Override
    public boolean isLocal() {
        return exploded;
    }

}
