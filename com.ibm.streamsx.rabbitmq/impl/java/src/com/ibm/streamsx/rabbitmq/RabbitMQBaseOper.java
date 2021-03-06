/*******************************************************************************
 * Copyright (C) 2015, MOHAMED-ALI SAID and International Business Machines
 * All Rights Reserved
 *******************************************************************************/

package com.ibm.streamsx.rabbitmq;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.logging.LogLevel;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.metrics.Metric;
import com.ibm.streams.operator.model.CustomMetric;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streamsx.rabbitmq.i18n.Messages;
import com.rabbitmq.client.Address;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;

@Libraries({ "opt/downloaded/*"/*, "@RABBITMQ_HOME@" */})
public class RabbitMQBaseOper extends AbstractOperator {

	private static final Boolean SSL_USE_SSL_DEFAULT = Boolean.FALSE;
	private static final String SSL_KEYSTORE_TYPE_DEFAULT = KeyStore.getDefaultType();
	private static final String SSL_KEYSTORE_ALGORITHM_DEFAULT = KeyManagerFactory.getDefaultAlgorithm();
	private static final String SSL_TRUSTSTORE_TYPE_DEFAULT = KeyStore.getDefaultType();
	private static final String SSL_TRUSTSTORE_ALGORITHM_DEFAULT = KeyManagerFactory.getDefaultAlgorithm();
	private static final String SSL_PROTOCOL_DEFAULT = "TLSv1.2";
	
	private static final String USE_SSL_PARAM_NAME = "useSSL";
	private static final String SSL_PROTOCOL_PARAM_NAME = "sslProtocol";
	private static final String KEYSTORE_TYPE_PARAM_NAME = "keyStoreType";
	private static final String KEYSTORE_ALGORITHM_PARAM_NAME = "keyStoreAlgorithm";
	private static final String KEYSTORE_PATH_PARAM_NAME = "keyStorePath";
	private static final String KEYSTORE_PASSWORD_PARAM_NAME = "keyStorePassword";
	private static final String TRUSTSTORE_TYPE_PARAM_NAME = "trustStoreType";
	private static final String TRUSTSTORE_ALGORITHM_PARAM_NAME = "trustStoreAlgorithm";
	private static final String TRUSTSTORE_PATH_PARAM_NAME = "trustStorePath";
	private static final String TRUSTSTORE_PASSWORD_PARAM_NAME = "trustStorePassword";
	
	
	protected Channel		channel			= null;
	protected Connection	connection		= null;
	protected String 		username		= "",			//$NON-NLS-1$
							password		= "",			//$NON-NLS-1$
							exchangeName 	= "",
							exchangeType	= "direct";		//$NON-NLS-1$ //$NON-NLS-2$
			
	protected List<String> hostAndPortList = new ArrayList<String>();
	protected Address[] addressArr; 
	private String vHost;
	private Boolean autoRecovery = true;
	private AtomicBoolean shuttingDown = new AtomicBoolean(false);
	protected boolean readyForShutdown = true;

	protected AttributeHelper messageHeaderAH = new AttributeHelper("message_header"), //$NON-NLS-1$
			routingKeyAH = new AttributeHelper("routing_key"), //$NON-NLS-1$
			messageAH = new AttributeHelper("message"); //$NON-NLS-1$

	private final static Logger trace = Logger.getLogger(RabbitMQBaseOper.class.getCanonicalName());
	protected Boolean usingDefaultExchange = false;
	private String URI = ""; //$NON-NLS-1$
	private long networkRecoveryInterval = 5000;
	
	protected	Metric isConnected;
	private		long   isConnectedValueOld = 0;				// by default we are not connected	
	private		Metric reconnectionAttempts;
	private		Metric reconnectionAttemptsLatestBatch;
	private		String appConfigName = "";					//$NON-NLS-1$
	private		String userPropName;
	private		String passwordPropName;
	
	/* SSL Parameters */
	private Boolean useSSL = SSL_USE_SSL_DEFAULT;
	private String sslProtocol = SSL_PROTOCOL_DEFAULT;
	
	private String keyStoreType = SSL_KEYSTORE_TYPE_DEFAULT;
	private String keyStoreAlgorithm = SSL_KEYSTORE_ALGORITHM_DEFAULT;
	private String keyStorePath;
	private String keyStorePassword;
	
	private String trustStoreType = SSL_TRUSTSTORE_TYPE_DEFAULT;
	private String trustStoreAlgorithm = SSL_TRUSTSTORE_ALGORITHM_DEFAULT;
	private String trustStorePath;
	private String trustStorePassword;
	

	@Parameter(name=SSL_PROTOCOL_PARAM_NAME, optional = true, 
			description="Specifies the SSL protocol to use. If not specified, the default value is \\\"TLSv1.2\\\".")
	public void setSslProtocol(String sslProtocol) {
		this.sslProtocol = sslProtocol;
	}
	
	@Parameter(name=USE_SSL_PARAM_NAME, optional = true, 
			description="Specifies whether an SSL connection should be created. If not specified, the default value is `false`.")
	public void setUseSSL(Boolean useSSL) {
		this.useSSL = useSSL;
	}
	
	@Parameter(name=KEYSTORE_ALGORITHM_PARAM_NAME, optional = true,
			description="Specifies the algorithm that was used to encrypt the keyStore. If not specified, the operator "
					+ "will use the JVM's default algorithm (typically `IbmX509`).")
	public void setKeyStoreAlgorithm(String keyStoreAlgorithm) {
		this.keyStoreAlgorithm = keyStoreAlgorithm;
	}

	@Parameter(name=KEYSTORE_PASSWORD_PARAM_NAME, optional = true,
			description="Specifies the password used to unlock the keyStore.")
	public void setKeyStorePassword(String keyStorePassword) {
		this.keyStorePassword = keyStorePassword;
	}

	@Parameter(name=KEYSTORE_PATH_PARAM_NAME, optional = true,
			description="Specifies the path to the keyStore file. This parameter is required if the **useSSL** "
					+ "parameter is set to `true`.")
	public void setKeyStorePath(String keyStorePath) {
		this.keyStorePath = keyStorePath;
	}
	
	@Parameter(name=KEYSTORE_TYPE_PARAM_NAME, optional = true,
			description="Specifies the keyStore type. If not specified, the operator will use "
					+ "the JVM's default type (typically `JKS`).")
	public void setKeyStoreType(String keyStoreType) {
		this.keyStoreType = keyStoreType;
	}

	@Parameter(name=TRUSTSTORE_ALGORITHM_PARAM_NAME, optional = true,
			description="Specifies the algorithm that was used to encrypt the trustStore. If not specified, the operator "
					+ "will use the JVM's default algorithm (typically `IbmX509`).")
	public void setTrustStoreAlgorithm(String trustStoreAlgorithm) {
		this.trustStoreAlgorithm = trustStoreAlgorithm;
	}
	
	@Parameter(name=TRUSTSTORE_PASSWORD_PARAM_NAME, optional = true,
			description="Specifies the password used to unlock the trustStore.")
	public void setTrustStorePassword(String trustStorePassword) {
		this.trustStorePassword = trustStorePassword;
	}
	
	@Parameter(name=TRUSTSTORE_PATH_PARAM_NAME, optional = true,
			description="Specifies the path to the trustStore file. This parameter is required if the **useSSL** "
					+ "parameter is set to `true`.")
	public void setTrustStorePath(String trustStorePath) {
		this.trustStorePath = trustStorePath;
	}
	
	@Parameter(name=TRUSTSTORE_TYPE_PARAM_NAME, optional = true,
			description="Specifies the trustStore type. If not specified, the operator will use "
					+ "the JVM's default type (typically `JKS`).")
	public void setTrustStoreType(String trustStoreType) {
		this.trustStoreType = trustStoreType;
	}
	
	
	/*
	 * Check that the necessary SSL parameters are specified when an SSL connection is required
	 */
	@ContextCheck(compile = false, runtime = true)
	public static void checkSSLParameters(OperatorContextChecker checker) {
		Set<String> paramNames = checker.getOperatorContext().getParameterNames();
		if(paramNames.contains(USE_SSL_PARAM_NAME)) {
			List<String> useSSLValue = checker.getOperatorContext().getParameterValues(USE_SSL_PARAM_NAME);
			if(useSSLValue.get(0).equals("true")) {
				// SSL connection is required, ensure that the path to the 
				// keystore and truststore are specified
				if(!paramNames.contains(KEYSTORE_PATH_PARAM_NAME)) {
					checker.setInvalidContext(Messages.getString("MISSING_SSL_PARAM", KEYSTORE_PATH_PARAM_NAME), new Object[0]);
				}
				
				if(!paramNames.contains(KEYSTORE_PASSWORD_PARAM_NAME)) {
					checker.setInvalidContext(Messages.getString("MISSING_SSL_PARAM", KEYSTORE_PASSWORD_PARAM_NAME), new Object[0]);
				}
				
				if(!paramNames.contains(TRUSTSTORE_PATH_PARAM_NAME)) {
					checker.setInvalidContext(Messages.getString("MISSING_SSL_PARAM", TRUSTSTORE_PATH_PARAM_NAME), new Object[0]);
				}				
			}
		}
	}
	
	/*
	 * The method checkParametersRuntime validates that the reconnection policy
	 * parameters are appropriate
	 */
	@ContextCheck(compile = false)
	public static void checkParametersRuntime(OperatorContextChecker checker) {		
		if((checker.getOperatorContext().getParameterNames().contains("appConfigName"))) { //$NON-NLS-1$
        	String appConfigName = checker.getOperatorContext().getParameterValues("appConfigName").get(0); //$NON-NLS-1$
			String userPropName = checker.getOperatorContext().getParameterValues("userPropName").get(0); //$NON-NLS-1$
			String passwordPropName = checker.getOperatorContext().getParameterValues("passwordPropName").get(0); //$NON-NLS-1$
			
			
			PropertyProvider provider = new PropertyProvider(checker.getOperatorContext().getPE(), appConfigName);
			
			String userName = provider.getProperty(userPropName);
			String password = provider.getProperty(passwordPropName);
			
			if(userName == null || userName.trim().length() == 0) {
				trace.log(LogLevel.ERROR, Messages.getString("PROPERTY_NOT_FOUND_IN_APP_CONFIG", userPropName, appConfigName)); //$NON-NLS-1$
				checker.setInvalidContext(
						Messages.getString("PROPERTY_NOT_FOUND_IN_APP_CONFIG"), //$NON-NLS-1$
						new Object[] {userPropName, appConfigName});
				
			}
			
			if(password == null || password.trim().length() == 0) {
				trace.log(LogLevel.ERROR, Messages.getString("PROPERTY_NOT_FOUND_IN_APP_CONFIG", passwordPropName, appConfigName)); //$NON-NLS-1$
				checker.setInvalidContext(
						Messages.getString("PROPERTY_NOT_FOUND_IN_APP_CONFIG"), //$NON-NLS-1$
						new Object[] {passwordPropName, appConfigName});
			
			}
        }
	}
	
	// add check for appConfig userPropName and passwordPropName
	@ContextCheck(compile = true)
	public static void checkParameters(OperatorContextChecker checker) {	
		// Make sure if appConfigName is specified then both userPropName and passwordPropName are needed
		checker.checkDependentParameters("appConfigName", "userPropName", "passwordPropName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		checker.checkDependentParameters("userPropName", "appConfigName", "passwordPropName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		checker.checkDependentParameters("passwordPropName", "appConfigName", "userPropName"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}
	
	
	
	public synchronized void initialize(OperatorContext context) throws Exception {
		// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
	}
	
	
	
	protected boolean newCredentialsExist() {
		PropertyProvider propertyProvider = null;
		boolean newProperties = false;
		
		if (!getAppConfigName().isEmpty()) {
			OperatorContext context = getOperatorContext();
			propertyProvider = new PropertyProvider(context.getPE(), getAppConfigName());
			if (propertyProvider.contains(userPropName)
					&& !username.equals(propertyProvider.getProperty(userPropName))) {
				newProperties = true;
			}
			if (propertyProvider.contains(passwordPropName)
					&& !password.equals(propertyProvider.getProperty(passwordPropName))) {
				newProperties = true;
			}
		}
		
		trace.log(TraceLevel.INFO,
				"newPropertiesExist() is returning a value of: " + newProperties); //$NON-NLS-1$
		
		
		return newProperties;
	}
	
	public void resetRabbitClient() throws KeyManagementException, MalformedURLException, NoSuchAlgorithmException, URISyntaxException, IOException, TimeoutException, InterruptedException, Exception {
		if (autoRecovery){
			trace.log(TraceLevel.WARN, "Resetting Rabbit Client."); //$NON-NLS-1$
			closeRabbitConnections();
			initializeRabbitChannelAndConnection();
			
		} else {
			trace.log(TraceLevel.INFO, "AutoRecovery was not enabled, so we are not resetting client."); //$NON-NLS-1$
		}
	}
	

	/*
	 * Setup connection and channel. If automatic recovery is enabled, we will reattempt 
	 * to connect every networkRecoveryInterval
	 */
	public void initializeRabbitChannelAndConnection() throws MalformedURLException, URISyntaxException, NoSuchAlgorithmException,
			KeyManagementException, IOException, TimeoutException, InterruptedException, OperatorShutdownException, FailedToConnectToRabbitMQException, Exception {
		do {	
			try {
				ConnectionFactory connectionFactory = setupConnectionFactory();
				
				// If we return from this without throwing an exception,
				// then we have successfully connected
				connection = setupNewConnection(connectionFactory, URI, addressArr);
				channel = initializeExchange();
				setIsConnectedValue(1);
				
				trace.log(TraceLevel.INFO,
						"Initializing channel connection to exchange: " + exchangeName //$NON-NLS-1$
								+ " of type: " + exchangeType + " as user: " + connectionFactory.getUsername()); //$NON-NLS-1$ //$NON-NLS-2$
				trace.log(TraceLevel.INFO,
						"Connection to host: " + connection.getAddress()); //$NON-NLS-1$
			}
			catch (IOException | TimeoutException e) {
				e.printStackTrace();
				trace.log(LogLevel.ERROR, Messages.getString("FAILED_TO_SETUP_CONNECTION", e.getMessage())); //$NON-NLS-1$
				if (autoRecovery == true){
					Thread.sleep(networkRecoveryInterval);
				}
			}
		} while ( autoRecovery == true && (connection == null || channel == null) && !shuttingDown.get());
		
		if (connection == null || channel == null) {
			setIsConnectedValue(0);
			throw new FailedToConnectToRabbitMQException(Messages.getString("FAILED_TO_INIT_CONNECTION_OR_CHANNEL_TO_SERVER")); //$NON-NLS-1$
		}
	}

	private ConnectionFactory setupConnectionFactory() throws Exception {
		ConnectionFactory connectionFactory = new ConnectionFactory();
		connectionFactory.setExceptionHandler(new RabbitMQConnectionExceptionHandler(this));
		connectionFactory.setAutomaticRecoveryEnabled(autoRecovery);
				
		if (autoRecovery) {
			connectionFactory.setNetworkRecoveryInterval(networkRecoveryInterval);
		}
		
		if(useSSL) {
			connectionFactory.useSslProtocol(createSSLContext());
		}
		
		if (URI.isEmpty()){
			configureUsernameAndPassword(connectionFactory);
			if (vHost != null)
				connectionFactory.setVirtualHost(vHost);
			
			addressArr = buildAddressArray(hostAndPortList);
			
		} else{
			//use specified URI rather than username, password, vHost, hostname, etc
			if (!username.isEmpty() | !password.isEmpty() | vHost != null | !hostAndPortList.isEmpty()){
				trace.log(TraceLevel.WARNING, "You specified a URI, therefore username, password" //$NON-NLS-1$
						+ ", vHost, and hostname parameters will be ignored."); //$NON-NLS-1$
			}
			connectionFactory.setUri(URI);
		}
		return connectionFactory;
	}

	private SSLContext createSSLContext() throws Exception {
		char[] keyStorePasswordCharArray = keyStorePassword.toCharArray();
		KeyStore ks = KeyStore.getInstance(keyStoreType);
		ks.load(new FileInputStream(keyStorePath), keyStorePasswordCharArray);
		
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(keyStoreAlgorithm);
		kmf.init(ks, keyStorePasswordCharArray);
		
		char[] trustStorePasswordCharArray = trustStorePassword != null ? trustStorePassword.toCharArray() : null;
		KeyStore tks = KeyStore.getInstance(trustStoreType);
		tks.load(new FileInputStream(trustStorePath), trustStorePasswordCharArray);
		
		TrustManagerFactory tmf = TrustManagerFactory.getInstance(trustStoreAlgorithm);
		tmf.init(tks);
		
		SSLContext c = SSLContext.getInstance(sslProtocol);
		c.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
		
		return c;
	}

	/*
	 * Attempts to make a connection and throws an exception if it fails 
	 */
	private Connection setupNewConnection(ConnectionFactory connectionFactory, String URI, Address[] addressArr)
			throws IOException, TimeoutException, InterruptedException, OperatorShutdownException {
		Connection connection = null;
		connection = getConnection(connectionFactory, URI, addressArr);
		if (connectionFactory.isAutomaticRecoveryEnabled()) {
			((Recoverable) connection).addRecoveryListener(new AutoRecoveryListener(this));
		}

		return connection;
	}

	private Connection getConnection(ConnectionFactory connectionFactory, String URI, Address[] addressArr)
			throws IOException, TimeoutException {
		Connection connection;
		if (URI.isEmpty()){
			connection = connectionFactory.newConnection(addressArr);
			trace.log(TraceLevel.INFO, "Creating a new connection based on an address list."); //$NON-NLS-1$
		} else {
			connection = connectionFactory.newConnection();
			trace.log(TraceLevel.INFO, "Creating a new connection based on a provided URI."); //$NON-NLS-1$
		}
		return connection;
	}

	/*
	 * Set the username and password either from the parameters provided or from
	 * the appConfig. appConfig credentials have higher precedence than parameter credentials. 
	 */
	private void configureUsernameAndPassword(ConnectionFactory connectionFactory) {
		// Lowest priority parameters first
		// We overwrite those values if we find them in the appConfig
		PropertyProvider propertyProvider = null;
		
		// Overwrite with appConfig values if present. 
		if (!getAppConfigName().isEmpty()) {
			OperatorContext context = getOperatorContext();
			propertyProvider = new PropertyProvider(context.getPE(), getAppConfigName());
			if (propertyProvider.contains(userPropName)) {
				username = propertyProvider.getProperty(userPropName);
			}
			if (propertyProvider.contains(passwordPropName)) {
				password = propertyProvider.getProperty(passwordPropName);
			}
		}

		if (!username.isEmpty()) {
			connectionFactory.setUsername(username);
			trace.log(TraceLevel.INFO, "Set username."); //$NON-NLS-1$
		} else {
			trace.log(TraceLevel.INFO,
					"Default username: " + connectionFactory.getUsername() ); //$NON-NLS-1$
		}
		
		if (!password.isEmpty()) {
			connectionFactory.setPassword(password);
			trace.log(TraceLevel.INFO, "Set password."); //$NON-NLS-1$
		} else {
			trace.log(TraceLevel.INFO,
					"Default password: " + connectionFactory.getPassword()); //$NON-NLS-1$
		}
	}

	private Channel initializeExchange() throws IOException {
		Channel channel = connection.createChannel();
		try{
			//check to see if the exchange exists if not then it is the default exchange
			if ( !exchangeName.isEmpty()){
				channel.exchangeDeclarePassive(exchangeName);
				trace.log(TraceLevel.INFO, "Exchange was found, therefore no exchange will be declared."); //$NON-NLS-1$
			} else {
				usingDefaultExchange = true;
				trace.log(TraceLevel.INFO, "Using the default exchange. Name \"\""); //$NON-NLS-1$
			}
		} catch (IOException e){
			// if exchange doesn't exist, we will create it
			// we must also create a new channel since last one erred
			channel = connection.createChannel();
			// declare non-durable, auto-delete exchange
			channel.exchangeDeclare(exchangeName, exchangeType, false, true, null);
			trace.log(TraceLevel.INFO, "Exchange was not found, therefore non-durable exchange will be declared."); //$NON-NLS-1$
		}
		return channel;
	}

	private Address[] buildAddressArray(List<String> hostsAndPorts) throws MalformedURLException {
		Address[] addrArr = new Address[hostsAndPorts.size()];
		int i = 0;
		for (String hostAndPort : hostsAndPorts){
			URL tmpURL = new URL("http://" + hostAndPort); //$NON-NLS-1$
			addrArr[i++] = new Address(tmpURL.getHost(), tmpURL.getPort());
			trace.log(TraceLevel.INFO, "Adding: " + tmpURL.getHost() + ":"+ tmpURL.getPort()); //$NON-NLS-1$ //$NON-NLS-2$
		}
		trace.log(TraceLevel.INFO, "Built address array: \n" + addrArr.toString()); //$NON-NLS-1$
		
		return addrArr;
	}

	public void shutdown() throws Exception {
		shuttingDown.set(true);
		closeRabbitConnections();
		// Need this to make sure that we return from the process method
		// before exiting shutdown
		while(!readyForShutdown){
			Thread.sleep(100);
		}
		super.shutdown();
	}


	private void closeRabbitConnections() {
		if (channel != null) {
			try {
				channel.close();
			} catch (Exception e){
				e.printStackTrace();
				trace.log(LogLevel.ALL, Messages.getString("EXCEPTION_AT_CHANNEL_CLOSE", e.toString())); //$NON-NLS-1$
			} finally {
				channel = null;
			}
		}
				
		if (connection != null){
			try {
				connection.close();
			} catch (Exception e) {
				e.printStackTrace();
				trace.log(LogLevel.ALL, Messages.getString("EXCEPTION_AT_CONNECTION_CLOSE", e.toString())); //$NON-NLS-1$
			} finally {
				setIsConnectedValue(0);
				connection = null;
			}
		}
	}

	
	
	public void initSchema(StreamSchema ss) throws Exception {
		Set<MetaType> supportedTypes = new HashSet<MetaType>();
		supportedTypes.add(MetaType.MAP);
		messageHeaderAH.initialize(ss, false, supportedTypes);
		supportedTypes.remove(MetaType.MAP);
		
		supportedTypes.add(MetaType.RSTRING);
		supportedTypes.add(MetaType.USTRING);
		
		routingKeyAH.initialize(ss, false, supportedTypes);
		
		supportedTypes.add(MetaType.BLOB);
		
		messageAH.initialize(ss, true, supportedTypes);

	}
	


	@Parameter(optional = true, description = "List of host and port in form: \\\"myhost1:3456\\\",\\\"myhost2:3456\\\".")
	public void setHostAndPort(List<String> value) {
		hostAndPortList.addAll(value);
	}

	@Parameter(optional = true, description = "Username for RabbitMQ authentication.")
	public void setUsername(String value) {
		username = value;
	}

	@Parameter(optional = true, description = "Password for RabbitMQ authentication.")
	public void setPassword(String value) {
		password = value;
	}
	
	@Parameter(optional = true, description = "This parameter specifies the name of application configuration that stores client credentials, "
			+ "the property specified via application configuration is overridden by the application parameters. "
			+ "The hierarchy of credentials goes: credentials from the appConfig beat out parameters (username and password). "
			+ "The valid key-value pairs in the appConfig are <userPropName>=<username> and <passwordPropName>=<password>, where "
			+ "<userPropName> and <passwordPropName> are specified by the corresponding parameters. "
			+ "If the operator loses connection to the RabbitMQ server, or it fails authentication, it will "
			+ "check for new credentials in the appConfig and attempt to reconnect if they exist. "
			+ "The attempted reconnection will only take place if automaticRecovery is set to true (which it is by default).")
	public void setAppConfigName(String appConfigName) {
		this.appConfigName  = appConfigName;
	}
	
	public String getAppConfigName() {
		return appConfigName;
	}
	
	@Parameter(optional = true, description = "This parameter specifies the property name of user name in the application configuration. If the appConfigName parameter is specified and the userPropName parameter is not set, a compile time error occurs.")
	public void setUserPropName(String userPropName) {
		this.userPropName = userPropName;
	}
    
	@Parameter(optional = true, description = "This parameter specifies the property name of password in the application configuration. If the appConfigName parameter is specified and the passwordPropName parameter is not set, a compile time error occurs.")
	public void setPasswordPropName(String passwordPropName) {
		this.passwordPropName = passwordPropName;
	}
	
	@Parameter(optional = true, description = "Optional attribute. Name of the RabbitMQ exchange type. Default direct.")
	public void setExchangeType(String value) {
		exchangeType = value;
	}
	
	@Parameter(optional = true, description = "Convenience URI of form: amqp://userName:password@hostName:portNumber/virtualHost. If URI is specified, you cannot specify username, password, and host.")
	public void setURI(String value) {
		URI  = value;
	}

	@Parameter(optional = true, description = "Name of the attribute for the message. Default is \\\"message\\\".")
	public void setMessageAttribute(String value) {
		messageAH.setName(value);
	}

	@Parameter(optional = true, description = "Name of the attribute for the routing_key. Default is \\\"routing_key\\\".")
	public void setRoutingKeyAttribute(String value) {
		routingKeyAH.setName(value);
	}

	@Parameter(optional = true, description = "Name of the attribute for the message_header. Schema of type must be Map<ustring,ustring>. Default is \\\"message_header\\\".")
	public void setMsgHeaderAttribute(String value) {
		messageHeaderAH.setName(value);
	}
	
	@Parameter(optional = true, description = "Set Virtual Host. Default is null.")
	public void setVirtualHost(String value) {
		vHost = value; 
	}
	
	@Parameter(optional = true, description = "Have connections to RabbitMQ automatically recovered. Default is true.")
	public void setAutomaticRecovery(Boolean value) {
		autoRecovery = value; 
	}
	
	public Boolean getAutoRecovery() {
		return autoRecovery;
	}

	@Parameter(optional = true, description = "If automaticRecovery is set to true, this is the interval (in ms) that will be used between reconnection attempts. The default is 5000 ms.")
	public void setSetNetworkRecoveryInterval(long value) {
		networkRecoveryInterval  = value; 
	}

	
	
	@CustomMetric(	name = "isConnected",
					kind = Metric.Kind.GAUGE,
					description = "Describes whether we are currently connected to the RabbitMQ server.")
	public void setIsConnectedNewMetric(Metric isConnected) {
		this.isConnected = isConnected;
	}
	
	
	
	public void setIsConnectedValue(long value) {
		synchronized(isConnected) {
			isConnected.setValue(value);
			isConnected.notifyAll();
			
			// isConnectedValueOld | isConnectedValue | Meaning
			//=====================|==================|=================================================
			//           0         |         0        | Not connected before, not connected yet => reconnectionAttempt				(increment reconnectionAttempts counters)
			//           0         |         1        | Not connected before, connected yet     => connection established event		(increment reconnectionAttempts counters)
			//           1         |         0        | Connected before, not connected yet     => connection lost event			(reset last batch counter)
			//           1         |         1        | Connected before, connected yet     	=> does this even happen? 			(do nothing)
			if( isConnectedValueOld == 0 ) {
				reconnectionAttempts.increment();
				reconnectionAttemptsLatestBatch.increment();
			}
			else if( isConnectedValueOld == 1 && value == 0) {
				reconnectionAttemptsLatestBatch.setValue(0);
			}
			else {
				// isConnectedValueOld == 1 && value == 1
				// => do nothing
			}
			isConnectedValueOld = value;
		}
	}
	
	
	
	@CustomMetric(	name = "reconnectionAttempts",
					kind = Metric.Kind.COUNTER,
					description = "The accumulated number of times we have attempted to reconnect since the operator has been started.")
	public void setReconnectionAttemptsMetric(Metric reconnectionAttempts) {
		this.reconnectionAttempts = reconnectionAttempts;
	}
	
	
	
	@CustomMetric(	name = "reconnectionAttemptsLatestBatch",
					kind = Metric.Kind.COUNTER,
					description = "The number of times we have attempted to reconnect to establish the last successful connection.")
	public void setReconnectionAttemptsLatestBatchMetric(Metric reconnectionAttemptsLatestBatch) {
		this.reconnectionAttemptsLatestBatch = reconnectionAttemptsLatestBatch;
	}
	
	
	
	public static final String BASE_DESC = "\\n\\n**AppConfig**: " //$NON-NLS-1$
			+ "The hierarchy of credentials goes: credentials from the appConfig beat out parameters (username and password). " //$NON-NLS-1$
			+ "The valid key-value pairs in the appConfig are <userPropName>=<username> and <passwordPropName>=<password>, where " //$NON-NLS-1$
			+ "<userPropName> and <passwordPropName> are specified by the corresponding parameters. " //$NON-NLS-1$
			+ "This operator will only automatically recover with new credentials from the appConfig if automaticRecovery " //$NON-NLS-1$
			+ "is set to true. "; //$NON-NLS-1$


}
