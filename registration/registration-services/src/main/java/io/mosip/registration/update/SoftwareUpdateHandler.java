package io.mosip.registration.update;

import static io.mosip.registration.constants.RegistrationConstants.APPLICATION_ID;
import static io.mosip.registration.constants.RegistrationConstants.APPLICATION_NAME;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.*;
import java.util.Map.Entry;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import io.micrometer.core.annotation.Counted;
import io.mosip.kernel.core.util.HMACUtils2;
import io.mosip.kernel.logger.logback.util.MetricTag;
import io.mosip.registration.context.ApplicationContext;
import io.mosip.registration.exception.RegBaseCheckedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import io.mosip.kernel.core.exception.ExceptionUtils;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.FileUtils;
import io.mosip.registration.config.AppConfig;
import io.mosip.registration.constants.LoggerConstants;
import io.mosip.registration.constants.RegistrationConstants;
import io.mosip.registration.dto.ResponseDTO;
import io.mosip.registration.service.BaseService;
import io.mosip.registration.service.config.GlobalParamService;

/**
 * This class will update the application based on comapring the versions of the
 * jars from the Manifest. The comparison will be done by comparing the Local
 * Manifest and the meta-inf.xml file. If there is any updation available in the
 * jar then the new jar gets downloaded and the old gets archived.
 * 
 * @author YASWANTH S
 *
 */
@Component
public class SoftwareUpdateHandler extends BaseService {

	/**
	 * Instance of {@link Logger}
	 */
	private static final Logger LOGGER = AppConfig.getLogger(SoftwareUpdateHandler.class);
	private static final String SLASH = "/";
	private static final String manifestFile = "MANIFEST.MF";
	private static final String libFolder = "lib";
	private static final String binFolder = "bin";
	private static final String lastUpdatedTag = "lastUpdated";
	private static final String SQL = "sql";
	private static final String exectionSqlFile = "initial_db_scripts.sql";
	private static final String rollBackSqlFile = "rollback_scripts.sql";
	private static final String versionTag = "version";
	private static final String MOSIP_SERVICES = "registration-services";
	private static final String MOSIP_CLIENT = "registration-client";

	private static Map<String, String> CHECKSUM_MAP;
	private String currentVersion;
	private String latestVersion;
	private Manifest localManifest;
	private Manifest serverManifest;
	private String latestVersionReleaseTimestamp;

	@Value("${mosip.reg.rollback.path}")
	private String backUpPath;

	@Value("${mosip.reg.client.url}")
	private String serverRegClientURL;

	@Value("${mosip.reg.xml.file.url}")
	private String serverMosipXmlFileUrl;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private GlobalParamService globalParamService;

	@Autowired
	private Environment environment;


	public ResponseDTO updateDerbyDB() {
		String version = ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.SERVICES_VERSION_KEY);
		LOGGER.info("Inside updateDerbyDB currentVersion : {} and {} : {}", currentVersion,
				RegistrationConstants.SERVICES_VERSION_KEY, version);
		if(version != null && currentVersion != null && !currentVersion.equalsIgnoreCase(version)) {
			return executeSqlFile(currentVersion, version);
		}
		return null;
	}


	/**
	 * It will check whether any software updates are available or not.
	 * <p>
	 * The check will be done by comparing the Local Manifest file version with the
	 * version of the server meta-inf.xml file
	 * </p>
	 * 
	 * @return Boolean true - If there is any update available. false - If no
	 *         updates available
	 */
	@Counted(recordFailuresOnly = true)
	public boolean hasUpdate() {
		LOGGER.info("Checking for any new updates");
		try {
			return !getCurrentVersion().equals(getLatestVersion());
		} catch (Throwable exception) {
			LOGGER.error("Failed to check if update is available or not", exception);
			return false;
		}
	}

	/**
	 * 
	 * @return Returns the current version which is read from the server meta-inf
	 *         file.
	 * @throws IOException
	 * @throws ParserConfigurationException
	 * @throws SAXException
	 */
	private String getLatestVersion() throws IOException, ParserConfigurationException, SAXException, RegBaseCheckedException {
		LOGGER.info("Checking for latest version started");
		// Get latest version using meta-inf.xml
		DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = documentBuilderFactory.newDocumentBuilder();
		org.w3c.dom.Document metaInfXmlDocument = db.parse(SoftwareUpdateUtil.download(getURL(serverMosipXmlFileUrl)));
		setLatestVersion(getElementValue(metaInfXmlDocument, versionTag));
		setLatestVersionReleaseTimestamp(getElementValue(metaInfXmlDocument, lastUpdatedTag));
		LOGGER.info("Checking for latest version completed");
		return latestVersion;
	}

	private String getElementValue(Document metaInfXmlDocument, String tagName) {
		NodeList list = metaInfXmlDocument.getDocumentElement().getElementsByTagName(tagName);
		String val = null;
		if (list != null && list.getLength() > 0) {
			NodeList subList = list.item(0).getChildNodes();

			if (subList != null && subList.getLength() > 0) {
				// Set Latest Version
				val = subList.item(0).getNodeValue();
			}
		}
		return val;
	}

	/**
	 * Get Current version of setup
	 * 
	 * @return current version
	 */
	public String getCurrentVersion() {
		LOGGER.info("Checking for current version started...");
		// Get Local manifest file
		try {
			if (getLocalManifest() != null) {
				setCurrentVersion((String) localManifest.getMainAttributes().get(Attributes.Name.MANIFEST_VERSION));
			}
		} catch (RegBaseCheckedException exception) {
			LOGGER.error(exception.getMessage(), exception);
		}
		LOGGER.info("Checking for current version completed : {}", currentVersion);
		return currentVersion;
	}

	public void doSoftwareUpgrade() {
		LOGGER.info("Updating latest version started");
		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
		String date = timestamp.toString().replace(":", "-") + "Z";
		File backupFolder = new File(backUpPath + SLASH + getCurrentVersion() + "_" + date);

		try {
			// Back Current Application
			backUpSetup(backupFolder);
			update();
			LOGGER.info("Updating to latest version completed.");
			return;
		} catch (Throwable t) {
			LOGGER.error("Failed with software upgrade", t);
		}

		try {
			rollBackSetup(backupFolder);
		} catch (io.mosip.kernel.core.exception.IOException e) {
			LOGGER.error("Failed to rollback setup", e);
		}
	}

	/**
	 * <p>
	 * Checks whteher the update is available or not
	 * </p>
	 * <p>
	 * If the Update is available:
	 * </p>
	 * <p>
	 * If the jars needs to be added/updated in the local
	 * </p>
	 * <ul>
	 * <li>Take the back-up of the current jars</li>
	 * <li>Download the jars from the server and add/update it in the local</li>
	 * </ul>
	 * <p>
	 * If the jars needs to be deleted in the local
	 * </p>
	 * <ul>
	 * <li>Take the back-up of the current jars</li>
	 * <li>Delete that particular jar from the local</li>
	 * </ul>
	 * <p>
	 * If there is any error occurs while updation then the restoration of the jars
	 * will happen by taking the back-up jars
	 * </p>
	 * 
	 * @throws Exception
	 *             - IOException
	 */
	@Counted(recordFailuresOnly = true)
	private void update() throws Exception {
		// fetch server manifest && replace local manifest with Server manifest
		setServerManifest();
		serverManifest.write(new FileOutputStream(manifestFile));
		setLocalManifest();
		SoftwareUpdateUtil.deleteUnknownJars(localManifest);

		Map<String, Attributes> localAttributes = localManifest.getEntries();
		for (Map.Entry<String, Attributes> entry : localAttributes.entrySet()) {
			File file = new File(libFolder + SLASH + entry.getKey());
			if(!file.exists() || !SoftwareUpdateUtil.validateJarChecksum(file, entry.getValue())) {
				String url = serverRegClientURL + latestVersion + SLASH + libFolder + SLASH + entry.getKey();
				try {
					if(file.delete()) {
						Files.copy(SoftwareUpdateUtil.download(url), file.toPath());
						LOGGER.info("Successfully deleted and downloaded the file : {}", entry.getKey());
					}
				} catch (IOException | RegBaseCheckedException e) {
					LOGGER.error("Failed to download {}", url, e);
				}
			}
		}

		setServerManifest(null);
		setLatestVersion(null);

		// Update global param of software update flag as false
		globalParamService.update(RegistrationConstants.IS_SOFTWARE_UPDATE_AVAILABLE,
				RegistrationConstants.DISABLE);
		globalParamService.update(RegistrationConstants.LAST_SOFTWARE_UPDATE,
				String.valueOf(Timestamp.valueOf(DateUtils.getUTCCurrentDateTime())));
	}

	private void backUpSetup(File backUpFolder) throws io.mosip.kernel.core.exception.IOException {
		LOGGER.info("Backup of current version started {}", backUpFolder);
		// bin backup folder
		File bin = new File(backUpFolder.getAbsolutePath() + SLASH + binFolder);
		bin.mkdirs();

		// lib backup folder
		File lib = new File(backUpFolder.getAbsolutePath() + SLASH + libFolder);
		lib.mkdirs();

		// manifest backup file
		File manifest = new File(backUpFolder.getAbsolutePath() + SLASH + manifestFile);

		FileUtils.copyDirectory(new File(binFolder), bin);
		FileUtils.copyDirectory(new File(libFolder), lib);
		FileUtils.copyFile(new File(manifestFile), manifest);

		for (File backUpFile : new File(backUpPath).listFiles()) {
			if (!backUpFile.getAbsolutePath().equals(backUpFolder.getAbsolutePath())) {
				FileUtils.deleteDirectory(backUpFile);
			}
		}

		globalParamService.update(RegistrationConstants.SOFTWARE_BACKUP_FOLDER,
				backUpFolder.getAbsolutePath());
		LOGGER.info("Backup of current version completed at {}", backUpFolder.getAbsolutePath());
	}

	private void setLocalManifest() throws RegBaseCheckedException {
		try {
			File localManifestFile = new File(manifestFile);
			if (localManifestFile.exists()) {
				localManifest = new Manifest(new FileInputStream(localManifestFile));
			}
		} catch (IOException e) {
			LOGGER.error("Failed to load local manifest file", e);
			throw new RegBaseCheckedException("REG-BUILD-003", "Local Manifest not found");
		}
	}

	private void setServerManifest() {
		String url = serverRegClientURL + latestVersion + SLASH + manifestFile;
		try {
			serverManifest = new Manifest(SoftwareUpdateUtil.download(url));
		} catch (IOException | RegBaseCheckedException e) {
			LOGGER.error("Failed to load server manifest file", e);
		}
	}

	/**
	 * The latest version timestamp will be taken from the server meta-inf.xml file.
	 * This timestamp will the be parsed in this method.
	 * 
	 * @return timestamp
	 */
	public Timestamp getLatestVersionReleaseTimestamp() {

		Calendar calendar = Calendar.getInstance();

		String dateString = latestVersionReleaseTimestamp;

		int year = Integer.valueOf(dateString.charAt(0) + "" + dateString.charAt(1) + "" + dateString.charAt(2) + ""
				+ dateString.charAt(3));
		int month = Integer.valueOf(dateString.charAt(4) + "" + dateString.charAt(5));
		int date = Integer.valueOf(dateString.charAt(6) + "" + dateString.charAt(7));
		int hourOfDay = Integer.valueOf(dateString.charAt(8) + "" + dateString.charAt(9));
		int minute = Integer.valueOf(dateString.charAt(10) + "" + dateString.charAt(11));
		int second = Integer.valueOf(dateString.charAt(12) + "" + dateString.charAt(13));

		calendar.set(year, month - 1, date, hourOfDay, minute, second);

		return new Timestamp(calendar.getTime().getTime());
	}

	/**
	 * This method will check whether any updation needs to be done in the DB
	 * structure.
	 * <p>
	 * If there is any updates available:
	 * </p>
	 * <p>
	 * Take the back-up of the current DB
	 * </p>
	 * <p>
	 * Run the Update queries from the sql file, which is downloaded from the server
	 * and available in the local
	 * </p>
	 * <p>
	 * If there is any error occurs during the update,then the rollback query will
	 * run from the sql file
	 * </p>
	 * 
	 * @param actualLatestVersion
	 *            latest version
	 * @param previousVersion
	 *            previous version
	 * @return response of sql execution
	 * @throws IOException
	 */
	@Counted(recordFailuresOnly = true)
	public ResponseDTO executeSqlFile(@MetricTag("newversion") String actualLatestVersion,
									  @MetricTag("oldversion") String previousVersion) {

		LOGGER.info("DB-Script files execution started from previous version : {} , To Current Version : {}",previousVersion, currentVersion);
		String newVersion = actualLatestVersion.split("-")[0];
		previousVersion = previousVersion.split("-")[0];

		ResponseDTO responseDTO = new ResponseDTO();
		try {
			LOGGER.info("Checking Started : " + newVersion + SLASH + exectionSqlFile);
			execute(SQL + SLASH + newVersion + SLASH + exectionSqlFile);
			LOGGER.info("Checking completed : " + newVersion + SLASH + exectionSqlFile);
			// Update global param with current version
			globalParamService.update(RegistrationConstants.SERVICES_VERSION_KEY, actualLatestVersion);
			setSuccessResponse(responseDTO, RegistrationConstants.SQL_EXECUTION_SUCCESS, null);
			LOGGER.info("DB-Script files execution completed");
			return responseDTO;
		} catch (Throwable exception) {
			LOGGER.error("Failed to execute db upgrade scripts", exception);
		}

		// ROLL BACK QUERIES
		try {
			LOGGER.info("Rollback started : " + newVersion + SLASH + rollBackSqlFile);
			execute(SQL + SLASH + newVersion + SLASH + rollBackSqlFile);
			LOGGER.info("Rollback completed : " + newVersion + SLASH + rollBackSqlFile);
			String backupPath = ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.SOFTWARE_BACKUP_FOLDER);
			if(backupPath != null) {
				rollBackSetup(new File(backupPath));
				globalParamService.update(RegistrationConstants.SOFTWARE_BACKUP_FOLDER, null);
			}
		} catch (Throwable exception) {
			LOGGER.error("Failed to execute db rollback scripts", exception);
		}

		setErrorResponse(responseDTO, RegistrationConstants.SQL_EXECUTION_FAILURE, null);
		return responseDTO;
	}

	private void execute(String path) throws IOException {
		try (InputStream inputStream = SoftwareUpdateHandler.class.getClassLoader().getResourceAsStream(path)) {

			LOGGER.info(LoggerConstants.LOG_REG_UPDATE, APPLICATION_NAME, APPLICATION_ID,
					inputStream != null ? path + " found" : path + " Not Found");

			if (inputStream != null) {
				runSqlFile(inputStream);
			}
		}
	}

	private void runSqlFile(InputStream inputStream) throws IOException {
		LOGGER.info(LoggerConstants.LOG_REG_UPDATE, APPLICATION_NAME, APPLICATION_ID, "Execution started sql file");

		try (InputStreamReader inputStreamReader = new InputStreamReader(inputStream)) {
			try (BufferedReader bufferedReader = new BufferedReader(inputStreamReader)) {

				String str;
				StringBuilder sb = new StringBuilder();
				while ((str = bufferedReader.readLine()) != null) {
					sb.append(str + "\n ");
				}

				List<String> statments = java.util.Arrays.asList(sb.toString().split(";"));

				for (String stat : statments) {
					if (!stat.trim().equals("")) {
						LOGGER.info(LoggerConstants.LOG_REG_UPDATE, APPLICATION_NAME, APPLICATION_ID,
								"Executing Statment : " + stat);
						jdbcTemplate.execute(stat);
					}
				}
			}
		}
		LOGGER.info(LoggerConstants.LOG_REG_UPDATE, APPLICATION_NAME, APPLICATION_ID, "Execution completed sql file");
	}

	private void rollBackSetup(File backUpFolder) throws io.mosip.kernel.core.exception.IOException {
		LOGGER.info("Replacing Backup of current version started");
		if(backUpFolder.exists()) {
			FileUtils.copyDirectory(new File(backUpFolder.getAbsolutePath() + SLASH + binFolder), new File(binFolder));
			FileUtils.copyDirectory(new File(backUpFolder.getAbsolutePath() + SLASH + libFolder), new File(libFolder));
			FileUtils.copyFile(new File(backUpFolder.getAbsolutePath() + SLASH + manifestFile), new File(manifestFile));
		}
		LOGGER.info("Replacing Backup of current version completed");
	}

	public Map<String, String> getJarChecksum() {
		Map<String, String> checksumMap = new HashMap<>();
		if(localManifest != null) {
			Map<String, java.util.jar.Attributes> localEntries = localManifest.getEntries();
			List<String> keys = localEntries.keySet().stream().filter( k -> k.contains(MOSIP_CLIENT) || k.contains(MOSIP_SERVICES)).collect(Collectors.toList());
			for(String key : keys) {
				checksumMap.put(key, localEntries.get(key).getValue(Attributes.Name.CONTENT_TYPE));
			}
		}
		return checksumMap;
	}

	private String getURL(String urlPostFix) {
		String upgradeServerURL = ApplicationContext.getStringValueFromApplicationMap(RegistrationConstants.MOSIP_UPGRADE_SERVER_URL);
		String url = String.format(urlPostFix, upgradeServerURL);
		url = serviceDelegateUtil.prepareURLByHostName(url);
		LOGGER.info("Upgrade server : {}", url);
		return url;
	}

	private void setServerManifest(Manifest serverManifest) {
		this.serverManifest = serverManifest;
	}

	private void setCurrentVersion(String currentVersion) {
		this.currentVersion = currentVersion;
	}

	private void setLatestVersion(String latestVersion) {
		this.latestVersion = latestVersion;
	}

	public void setLatestVersionReleaseTimestamp(String latestVersionReleaseTimestamp) {
		this.latestVersionReleaseTimestamp = latestVersionReleaseTimestamp;
	}

	private Manifest getLocalManifest() throws RegBaseCheckedException {
		return localManifest;
	}
}
