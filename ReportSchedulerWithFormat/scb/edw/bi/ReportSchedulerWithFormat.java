/**
 * 
 */
package scb.edw.bi;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.jdom2.input.SAXBuilder;
import org.jdom2.input.sax.XMLReaders;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.json.*;
import org.jdom2.Attribute;
import org.jdom2.Content;
import org.jdom2.DataConversionException;
import org.jdom2.Document;
import org.jdom2.Element;
import org.xml.sax.InputSource;

/**
 * @author wanchai
 *
 */
public class ReportSchedulerWithFormat {

	/**
	 * @param args
	 */

	private static HashMap<String, String> PARAMS_INFO = new HashMap<String, String>();
	private static HashMap<String, String[]> PROMPT_INFO = new HashMap<String, String[]>();
	private static String JSONDatetimeFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
	private static String EDWBIDatetimeFormat = "MM/dd/yyyy HH:mm:ss";
	private static Integer RETRY_INTERVAL_TIME = 2;
	private static Integer RETRY_INTERVAL_SECONDS = 60;
	private String LOGONTOKEN, LOGONUSER, LOGONPASSWORD;
	private String CMSSERVER;
	private String CMSPORT = "6405", CMSPROTOCOL = "http";
	private boolean ISTRUSTED_LOGON;

	private String DOC_CUID, DOC_NAME;
	private int DOC_ID, DOC_INSTANCE_ID;
	static final int intDefPollingFrequency = 6; // in seconds
	static final int intDefTimeoutThreshold = 4; // in hours

	// POLLING FREQUENCY, 6 SECOND DEFAULT
	int intPollingFrequency = 1000 * intDefPollingFrequency;

	// TIMEOUT THRESHOLD, 4 HOUR DEFAULT
	int intTimeOutThreshold = 1000 * 60 * 60 * intDefTimeoutThreshold;

	public static enum WEBRESOURCE {
		schedules, logon, logoff, infostore, parameters, scheduleForms
	}

	public static void main(String[] args) {
		int params_pos = 0;
		String param_name = "", param_value = "", prompt_name = "", prompt_value = "";
		while (params_pos < args.length) {
			if (args[params_pos].trim().startsWith("/")) {
				param_name = args[params_pos].toLowerCase().trim().substring(1);
				param_value = args[params_pos + 1];
				switch (param_name) {
				case "setprompt":
					prompt_name = param_value.split("=")[0];
					prompt_value = param_value.replace(prompt_name + "=", "");
					if (PROMPT_INFO.containsKey(prompt_name)) {
						String[] temp = PROMPT_INFO.get(prompt_name).clone();
						String[] array = new String[temp.length + 1];
						System.arraycopy(temp, 0, array, 0, temp.length);
						array[temp.length - 1] = prompt_value;
						PROMPT_INFO.put(prompt_name, array);
					} else {
						String[] array = new String[] { prompt_value };
						PROMPT_INFO.put(prompt_name, array);
					}
					break;
				default:
					PARAMS_INFO.put(param_name, param_value);
					break;
				}
				if (!args[params_pos + 1].trim().startsWith("/")) {
					params_pos = params_pos + 2;
				} else {
					params_pos++;
				}
			} else {
				params_pos++;
			}
		}
		ReportSchedulerWithFormat scheduler = null;
		if (PARAMS_INFO.containsKey("password")) {
			scheduler = new ReportSchedulerWithFormat(PARAMS_INFO.get("cms"), PARAMS_INFO.get("username"),
					PARAMS_INFO.get("password"));
		} else {
			scheduler = new ReportSchedulerWithFormat(PARAMS_INFO.get("cms"), PARAMS_INFO.get("username"));
		}
		if (PARAMS_INFO.containsKey("reportcuid"))
			scheduler.setDocumentId(PARAMS_INFO.get("reportcuid"));
		else
			scheduler.setDocumentId(Integer.parseInt(PARAMS_INFO.get("reportid")));

		scheduler.setDocumentInfo();

		// scheduler.schedule();
		scheduler.scheduleXml();

		scheduler.logoffCMS();
	}

	void printUsage() {
		// OUTPUT USAGE INFO
		// System.out.println("ScheduleReport: Commandline parameter " +
		// args[i].toLowerCase() + " not understood");
		System.out.println("ReportSchedulerWithFormat: Usage");
		System.out.println("/debug Print trace messages");
		System.out.println("/warn Print warning messages");
		System.out.println("/noerror Suppress error messages");
		System.out.println("/username <Username>");
		System.out.println("/password <Password> [Optional for Trusted Connection]");
		System.out.println("/cms <CMS>");
		System.out.println("/authentication <Authentication>");
		System.out.println("/pollingfrequency <Polling  Frequency (in milliseconds)> [Default: "
				+ intDefPollingFrequency + " sec]");
		System.out.println("/timeoutthreshold <Time Out Threshold (in milliseconds)> [Default: "
				+ intDefTimeoutThreshold + " hrs]");
		System.out.println("/reportid <Report ID>");
		System.out.println("/reportname <Report Name>");
		System.out.println(
				"/reporttype <Report Type> (eg CrystalEnterprise.Webi, CrystalEnterprise.FullClient, CrystalEnterprise.Report)");
		System.out.println("/reportfolder <rootFolder>/<subFolder>/<subFolder>");
		System.out.println("/setprompt <PromptName>=<Value1>;<value2>");

	}

	private void setDocumentId(String docCUID) {
		// TODO Auto-generated method stub
		this.DOC_CUID = docCUID;
	}

	private void setDocumentId(int docID) {
		// TODO Auto-generated method stub
		this.DOC_ID = docID;
	}

	public ReportSchedulerWithFormat(String cmsServer, String logonUser, String logonPassword) {
		this.CMSSERVER = cmsServer;
		this.LOGONUSER = logonUser;
		this.LOGONPASSWORD = logonPassword;
		this.ISTRUSTED_LOGON = false;
		this.logonCMS();
	}

	public ReportSchedulerWithFormat(String cmsServer, String logonUser) {
		this.CMSSERVER = cmsServer;
		this.LOGONUSER = logonUser;
		this.ISTRUSTED_LOGON = true;
		this.logonCMS();
	}

	public static String convertDateToString(LocalDateTime date) {
		// DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
		// LocalDate date = LocalDate.parse(input, formatter);
		// SimpleDateFormat df = new SimpleDateFormat(JSONDatetimeFormat);
		// String valueAsString = df.format(date);
		ZonedDateTime zDate = ZonedDateTime.of(date, ZoneId.systemDefault());
		String valueAsString = zDate.format(DateTimeFormatter.ofPattern(JSONDatetimeFormat));
		return valueAsString;
	}

	public static String convertDateToString(String date, String format) {

		LocalDateTime startDate = LocalDateTime.now();
		DateFormat df = new SimpleDateFormat(format);
		try {
			startDate = LocalDateTime.parse(date, DateTimeFormatter.ofPattern(format));
			return convertDateToString(startDate);
		} catch (Exception e) {
			return "";
		}
	}

	public static String convertDateToString(String date) {
		// DateTimeFormatter formatter =
		// DateTimeFormatter.ofPattern(DateTimeFormatter.ISO_INSTANT);
		// LocalDate lDate = LocalDate.parse(date,
		// DateTimeFormatter.ISO_INSTANT);
		return convertDateToString(date, EDWBIDatetimeFormat);
	}

	public static String updateRESTFulPayload(String payload, String ContentType, String AtrributePath,
			String AtrributeName, Object AtrributeValue) {
		if (ContentType.toLowerCase() == "json") {
			String _payLoad = "";
			try {
				JSONObject test = new JSONObject(payload);
				test = updateJsonObjValue(test, AtrributePath, AtrributeName, AtrributeValue, 0);
				_payLoad = test.toString();
				// System.out.println(test.toString());
			} catch (JSONException e) {
				// System.out.println(e.getMessage());
			}
			return _payLoad;
		} else {
			return "";
		}
	}

	public static JSONObject updateJsonObjValue(JSONObject jObj, String keyMain, String keyName, Object value,
			int level) {
		JSONObject currJsonObj = null;
		JSONArray currJsonArry = null;
		String currKeyName = "", currentMainKey = "";
		int currLevel = level;
		String[] splitKeyMain = keyMain.split("/");
		boolean isDeepestNode = (splitKeyMain.length <= (level + 1));

		if (splitKeyMain.length > currLevel) {
			currentMainKey = splitKeyMain[currLevel];
		}

		try {
			Iterator<?> iterator = jObj.keys();
			if (!iterator.hasNext()) {
				System.out.println(level + ": " + keyMain + " O# " + currKeyName);
			}
			while (iterator.hasNext()) {
				currKeyName = (String) iterator.next();
				if (currentMainKey.compareTo(currKeyName) == 0 && !isDeepestNode) {
					currLevel++;
					// "Mark to prevent double increasing"
					currentMainKey = currentMainKey + "(used)";
					// System.out.println(currKeyName + ": " +
					// jObj.getString(currKeyName));
				}

				if (jObj.get(currKeyName) instanceof JSONObject) {
					currJsonObj = jObj.getJSONObject(currKeyName);
					// System.out.println(level + ": " + keyMain + " O# " +
					// currKeyName);
					if (isDeepestNode && currentMainKey == currKeyName) {
						jObj.accumulate(currKeyName, value);
					} else {
						currJsonObj = updateJsonObjValue(currJsonObj, keyMain, keyName, value, currLevel);
					}
				} else if (jObj.get(currKeyName) instanceof JSONArray) {
					currJsonArry = jObj.getJSONArray(currKeyName);
					// System.out.println(level + ": " + keyMain + " O# " +
					// currKeyName);
					currJsonArry = updateJsonArrayValue(currJsonArry, keyMain, keyName, value, currLevel);
				} else {
					if (isDeepestNode && currentMainKey.compareTo(currKeyName) == 0
							&& jObj.get(currKeyName).toString().compareTo(keyName) == 0) {
						// Do deepest action
						if (currKeyName.compareTo("name") == 0) {
							// currJsonObj.getJSONObject("values").accumulate("value",
							// value);
							currJsonObj.getJSONObject("values").put("value", value);
						}
					}
					// System.out.println(
					// level + ": " + keyMain + " O# " + currKeyName + "=>" +
					// jObj.getString(currKeyName));
				}
				// if (currentMainKey.endsWith("(used)"))
				// break;
			}
		} catch (JSONException e) {

		}
		return jObj;
	}

	public static JSONArray updateJsonArrayValue(JSONArray jObj, String keyMain, String keyName, Object value,
			int level) {

		JSONObject currJsonObj = null;
		JSONArray currJsonArry = null;
		// String currKeyName = "", currentMainKey = "";
		int currLevel = level;

		// if (keyMain.split("/").length > currLevel) {
		// currentMainKey = keyMain.split("/")[currLevel];
		// }

		try {
			for (int i = 0; i < jObj.length(); i++) {
				if (jObj.get(i) instanceof JSONObject) {
					currJsonObj = jObj.getJSONObject(i);
					// System.out.println(level + ": " + keyMain + " A# " +
					// currKeyName);
					currJsonObj = updateJsonObjValue(currJsonObj, keyMain, keyName, value, currLevel);
				} else if (jObj.get(i) instanceof JSONArray) {
					currJsonArry = jObj.getJSONArray(i);
					// System.out.println(level + ": " + keyMain + " A# " +
					// currKeyName);
					currJsonArry = updateJsonArrayValue(currJsonArry, keyMain, keyName, value, currLevel);
				} else {
					// System.out.println(level + ": " + keyMain + " A# " +
					// currKeyName + "=>" + jObj.getString(i));
				}
			}
		} catch (JSONException e) {

		}
		return jObj;
	}

	private String getCMSURL() {
		return String.format("%s://%s:%s", this.CMSPROTOCOL, this.CMSSERVER, this.CMSPORT);
	}

	private String getWebResource(WEBRESOURCE webResource) {
		switch (webResource) {
		case logon:
			if (this.ISTRUSTED_LOGON)
				return this.getCMSURL() + "/biprws/logon/trusted";
			else
				return this.getCMSURL() + "/biprws/logon/long";
		case logoff:
			return this.getCMSURL() + "/biprws/logoff";
		default:
			break;
		}
		return "";
	}

	private String getWebResource(WEBRESOURCE webResource, int docId) {

		switch (webResource) {
		case parameters:
			return this.getCMSURL() + "/biprws/raylight/v1/documents/" + docId + "/parameters";
		case schedules:
			return this.getCMSURL() + "/biprws/raylight/v1/documents/" + docId + "/schedules";
		case infostore:
			return this.getCMSURL() + "/biprws/raylight/v1/documents/" + docId;
		case scheduleForms:
			return this.getCMSURL() + "/biprws/infostore/" + docId + "/scheduleForms";
		default:
			break;

		// schedules
		}
		return "";
	}

	private String getWebResource(WEBRESOURCE webResource, String docCuid) {
		switch (webResource) {
		case infostore:
			return this.getCMSURL() + "/biprws/raylight/v1/documents/cuid_" + docCuid;
		default:
			break;

		// schedules
		}
		return "";

	}

	private void logonCMS() {
		JSONObject jsonCMSLogon = this.getCMSLogonTemplate();
		try {
			jsonCMSLogon.put("userName", this.LOGONUSER);
			jsonCMSLogon.put("password", this.LOGONPASSWORD);
			jsonCMSLogon = this.getLogonToken(jsonCMSLogon);
			// X-SAP-LogonToken
			this.LOGONTOKEN = '"' + jsonCMSLogon.getString("logonToken") + '"';
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void logoffCMS() {
		JSONObject jsonLogoff = this.postRESTFul(this.getWebResource(WEBRESOURCE.logoff), null);
		System.out.println(jsonLogoff.toString());
	}

	private JSONObject getCMSLogonTemplate() {
		return this.getRESTFul(this.getWebResource(WEBRESOURCE.logon));
	}

	private JSONObject getLogonToken(JSONObject sjonLogonInfo) {
		if (this.ISTRUSTED_LOGON)
			return this.getRESTFul(this.getWebResource(WEBRESOURCE.logon));
		else
			return this.postRESTFul(this.getWebResource(WEBRESOURCE.logon), sjonLogonInfo);
	}

	private void setDocumentInfo() {
		JSONObject jsonDocInfo = null;
		try {
			if (this.DOC_CUID != null && this.DOC_CUID != "") {
				jsonDocInfo = this.getRESTFul(this.getWebResource(WEBRESOURCE.infostore, this.DOC_CUID));
				this.DOC_ID = Integer.parseInt(jsonDocInfo.getJSONObject("document").getString("id"));
			} else {
				jsonDocInfo = this.getRESTFul(this.getWebResource(WEBRESOURCE.infostore, this.DOC_ID));
				this.DOC_CUID = jsonDocInfo.getJSONObject("document").getString("cuid");
			}

			this.DOC_NAME = jsonDocInfo.getJSONObject("document").getString("name");
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	private void schedule() {
		// testObj = this.getRESTFul(this.getWebResource("schedules",
		// reportId));
		JSONObject jsonReportParameters = this.getRESTFul(this.getWebResource(WEBRESOURCE.parameters, this.DOC_ID));
		jsonReportParameters = this.setParameterValues(jsonReportParameters);
		JSONObject jsonSchedulePayload = this.setWebiPromptV1(jsonReportParameters);
		// JSONObject jsonSchedulePayload =
		// this.setWebiPrompt(jsonReportParametersA);

		try {
			LocalDateTime scheduleDate = LocalDateTime.now().plusMinutes(1);
			jsonSchedulePayload.getJSONObject("schedule").getJSONObject("once").put("startdate",
					convertDateToString(scheduleDate));
		} catch (JSONException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

		JSONObject jsonInstanceParams = this.putRESTFul(this.getWebResource(WEBRESOURCE.parameters, this.DOC_ID),
				jsonReportParameters);
		JSONObject jsonReportInstance = this.postRESTFul(this.getWebResource(WEBRESOURCE.schedules, this.DOC_ID),
				jsonSchedulePayload);
		if (jsonReportInstance.has("success")) {
			JSONObject jsonInstanceInfo;
			try {
				System.out.println(jsonInstanceParams.toString());
				jsonInstanceInfo = jsonReportInstance.getJSONObject("success");
				this.DOC_INSTANCE_ID = Integer.parseInt(jsonInstanceInfo.getString("id"));
				System.out.println(jsonInstanceInfo.getString("message"));
				// System.out.println(jsonReportParameters.toString());
				// jsonReportParameters =
				// this.getRESTFul(this.getWebResource(WEBRESOURCE.parameters,
				// this.DOC_ID));

			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else {
			throw new RuntimeException("Report schedule failed");
		}
	}

	private void scheduleXml() {
		// testObj = this.getRESTFul(this.getWebResource("schedules",
		// reportId));
		Document xmlReportParameters = this.getRESTFulXml(this.getWebResource(WEBRESOURCE.parameters, this.DOC_ID));
		// JSONObject jsonReportParameters =
		// this.getRESTFul(this.getWebResource(WEBRESOURCE.parameters,
		// this.DOC_ID));
		xmlReportParameters = this.setParameterXmlValues(xmlReportParameters);
		Document xmlSchedulePayload = this.setWebiPromptV1Xml(xmlReportParameters);

		// JSONObject jsonSchedulePayload =
		// this.setWebiPrompt(jsonReportParametersA);
		// Document xmlInstanceParams =
		// this.putRESTFulXml(this.getWebResource(WEBRESOURCE.parameters,
		// this.DOC_ID),
		// jsonReportParameters);
		Document xmlReportUpdatedParams = this.putRESTFulXml(this.getWebResource(WEBRESOURCE.parameters, this.DOC_ID),
				new Document(xmlSchedulePayload.getRootElement().getChild("parameters").detach()));
		xmlSchedulePayload.removeContent(xmlSchedulePayload.getRootElement().getChild("parameters"));
		System.out.println(getXMLString(xmlReportUpdatedParams));
		Document xmlReportInstance = this.postRESTFulXml(this.getWebResource(WEBRESOURCE.schedules, this.DOC_ID),
				new Document());
		// System.out.println(getXMLString(xmlReportInstance));
		if (xmlReportInstance.getRootElement().getName().compareToIgnoreCase("success") == 0) {
			// JSONObject jsonInstanceInfo;
			// System.out.println(jsonInstanceParams.toString());
			// String InstanceInfo =
			// xmlSchedulePayload.getProperty("success").toString();
			// this.DOC_INSTANCE_ID =
			// Integer.parseInt(InstanceInfo.item(0).getAttributes().get
			// System.out.println(jsonInstanceInfo.getString("message"));
			// System.out.println(jsonReportParameters.toString());
			// jsonReportParameters =
			// this.getRESTFul(this.getWebResource(WEBRESOURCE.parameters,
			// this.DOC_ID));
		} else {

			System.out.println(getXMLString(xmlReportInstance));
			throw new RuntimeException("Report schedule failed");
		}
	}

	private JSONObject setParameterValues(JSONObject jsonReportParameters) {
		// TODO Auto-generated method stub
		JSONArray jsonReportParametersA = null;
		int ERROR_CODE = 0;
		String ERROR_MSG = "";
		try {
			jsonReportParametersA = jsonReportParameters.getJSONObject("parameters").getJSONArray("parameter");
			for (int i = 0; i < jsonReportParametersA.length(); i++) {
				if (jsonReportParametersA.get(i) instanceof JSONObject) {
					String paramName = "", paramDataType = "", newVal = "";
					String isOptional = "", paramType = "", cardinality = "";

					try {
						JSONObject jsonParam = jsonReportParametersA.getJSONObject(i);
						JSONObject jsonAnswer = jsonParam.getJSONObject("answer");
						JSONObject jsonParamValues = jsonAnswer.getJSONObject("values");
						JSONObject jsonParamInfo = jsonAnswer.getJSONObject("info");
						JSONObject jsonParamInfoValues = null;// =
																// jsonParamInfo.getJSONObject("values");
						if (jsonParamInfo.has("values")) {
							jsonParamInfoValues = jsonParamInfo.getJSONObject("values");
						}
						jsonParamInfo.put("keepLastValues", "true");
						paramName = jsonParam.getString("name");
						paramType = jsonParam.getString("@type");
						isOptional = jsonParam.optString("@optional", "false");
						paramDataType = jsonAnswer.getString("@type");
						cardinality = jsonParamInfo.optString("@cardinality", "single");
						String[] promptValues = PROMPT_INFO.get(paramName);
						if (PROMPT_INFO.containsKey(paramName) && promptValues != null && promptValues.length > 0) {
							if (cardinality.compareToIgnoreCase("single") == 0) {
								if (paramDataType.compareToIgnoreCase("dateTime") == 0)
									newVal = convertDateToString(promptValues[0]);
								else
									newVal = promptValues[0];
								jsonParamValues.put("value", newVal);
								if (jsonParamInfoValues != null)
									jsonParamInfoValues.put("value", newVal);
							} else {
								jsonParamValues.remove("value");
								for (int j = 0; j < promptValues.length; j++) {
									if (paramDataType == "dateTime")
										newVal = convertDateToString(promptValues[j]);
									else
										newVal = promptValues[j];
									jsonParamValues.accumulate("value", newVal);
									if (jsonParamInfoValues != null)
										jsonParamInfoValues.put("value", newVal);
								}
							}
							// System.out.println(String.format("Name:%s\nData
							// Type: %s\nOptional:%s\nCardinality: %s",
							// paramName, paramDataType, isOptional,
							// cardinality));
						} else {
							if (isOptional.compareToIgnoreCase("false") == 0) {
								ERROR_CODE = -1;
								ERROR_MSG = ERROR_MSG + "ERROR: Prompt[\"" + paramName
										+ "\"]'s value is required to set!\n";
								// System.out.println("ERROR: Prompt(" +
								// paramName + "') value is required to set!");
								// System.out.println(String.format("Name:%s\nData
								// Type: %s\nOptional:%s\nCardinality: %s",
								// paramName, paramDataType, isOptional,
								// cardinality));
							} else {
								jsonParamValues.put("value", "");
								System.out.println("WARNING: Prompt(" + paramName + "') value was not set!");
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
						continue;
					}
				}
			}
			if (ERROR_CODE != 0) {
				throw new IllegalArgumentException("Required parameter(s) wasn't given value:\n" + ERROR_MSG);
			}
			System.out.println(jsonReportParametersA.toString());
			return jsonReportParameters;
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return jsonReportParameters;

	}

	private Document setParameterXmlValues(Document xmlReportParameters) {
		// TODO Auto-generated method stub
		// JSONArray jsonReportParametersA = null;
		int ERROR_CODE = 0;
		String ERROR_MSG = "";
		try {
			Element rootElement = xmlReportParameters.getRootElement();
			Iterator<Element> childrenElement = rootElement.getChildren().iterator();

			while (childrenElement.hasNext()) {
				Element parameterElement = childrenElement.next();
				if (parameterElement.getName() == "parameter") {

					// parameterElement.setName("technicalName")''
					Element nameElement = parameterElement.getChild("name");
					//nameElement.setName("technicalName");
					Attribute optionalAttr = parameterElement.getAttribute("optional");
					Element answerElement = parameterElement.getChild("answer");
					boolean isoptional = false;
					try {
						isoptional = optionalAttr.getBooleanValue();
					} catch (DataConversionException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}

					if (answerElement != null && nameElement != null) {
						String[] promptValues = PROMPT_INFO.get(nameElement.getText());
						Element valuesElement = answerElement.getChild("values");
						valuesElement.removeContent();
						if (promptValues != null && promptValues.length > 0) {
							Element infoElement = answerElement.getChild("info");
							// Element valuesElement =
							// answerElement.getChild("values");
							Attribute cardinalityAttr = infoElement.getAttribute("cardinality");
							Attribute dataTypeAttr = answerElement.getAttribute("type");
							String paramValue = null;
							if (cardinalityAttr == null
									|| cardinalityAttr.getValue().compareToIgnoreCase("single") == 0) {
								if (dataTypeAttr.getValue().compareToIgnoreCase("dateTime") == 0) {
									paramValue = convertDateToString(promptValues[0]);
								} else {
									paramValue = promptValues[0];
								}
								valuesElement.addContent(new Element("value"));
								valuesElement.getChild("value").setText(paramValue);

							} else {
								for (int j = 0; j < promptValues.length; j++) {
									if (dataTypeAttr.getValue().compareToIgnoreCase("dateTime") == 0) {
										paramValue = convertDateToString(promptValues[j]);
									} else {
										paramValue = promptValues[j];
									}
									Element valueElement = new Element("value");
									valueElement.setText(paramValue);
									valuesElement.addContent(valueElement);
								}
							}
						} else if (!isoptional) {
							valuesElement.addContent(new Element("value"));
							valuesElement.getChild("value").setText(null);
							ERROR_CODE = -1;
							ERROR_MSG = ERROR_MSG + "ERR: " + nameElement.getText() + ", value is not set.\n";
						} else {
							valuesElement.addContent(new Element("value"));
							valuesElement.getChild("value").setText(null);
							ERROR_MSG = ERROR_MSG + "WRN: " + nameElement.getText() + ", value is not set.\n";
						}
					}
				}
			}

			if (ERROR_CODE != 0) {
				throw new IllegalArgumentException("Required parameter(s) wasn't given value:\n" + ERROR_MSG);
			} else if (!ERROR_MSG.isEmpty()) {
				System.out.println(ERROR_MSG);
			}
			return xmlReportParameters;
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		}
		return xmlReportParameters;

	}

	private JSONObject setWebiPrompt(JSONArray jsonRseportParameters) {
		// TODO Auto-generated method stub
		JSONObject jsonSchedulePayload = this
				.getRESTFul(this.getWebResource(WEBRESOURCE.scheduleForms, this.DOC_ID) + "/now");
		try {
			/*
			 * jsonSchedulePayload.put("schedule", new JSONObject()); JSONObject
			 * jsonSchedulePayloadRoot =
			 * jsonSchedulePayload.getJSONObject("schedule");
			 * jsonSchedulePayloadRoot.put("name", this.DOC_NAME + "_TEST");
			 * jsonSchedulePayloadRoot.put("format ", new JSONObject());
			 * jsonSchedulePayloadRoot.getJSONObject("format ").put("type",
			 * "pdf"); jsonSchedulePayloadRoot.put("destination ", new
			 * JSONObject());
			 * jsonSchedulePayloadRoot.getJSONObject("destination ").put(
			 * "inbox", new JSONObject()); jsonSchedulePayloadRoot.put("once",
			 * new JSONObject());
			 * jsonSchedulePayloadRoot.getJSONObject("once").put(
			 * "retriesAllowed", RETRY_INTERVAL_TIME);
			 * jsonSchedulePayloadRoot.getJSONObject("once").put(
			 * "retryIntervalInSeconds", RETRY_INTERVAL_SECONDS);
			 * jsonSchedulePayloadRoot.getJSONObject("once").put("startdate",
			 * convertDateToString(LocalDateTime.now()));
			 * jsonSchedulePayloadRoot.getJSONObject("once").put("enddate",
			 * convertDateToString("9999-12-31 23:59:59",
			 * "yyyy-MM-dd HH:mm:ss"));
			 */
			// System.out.println(jsonSchedulePayload.toString());
			// JSONObject jsonSchedulePayloadRoot =
			// jsonSchedulePayload.getJSONObject("schedule");
			jsonSchedulePayload.put("parameters", jsonRseportParameters);
			// System.out.println(jsonSchedulePayload.toString());
			return jsonSchedulePayload;
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return jsonSchedulePayload;
	}

	private JSONObject setWebiPromptV1(JSONObject jsonRseportParameters) {
		// TODO Auto-generated method stub
		JSONObject jsonSchedulePayload = new JSONObject();
		try {
			jsonSchedulePayload.put("schedule", new JSONObject());
			JSONObject jsonSchedulePayloadRoot = jsonSchedulePayload.getJSONObject("schedule");
			jsonSchedulePayloadRoot.put("name", this.DOC_NAME + "_TEST");
			jsonSchedulePayloadRoot.put("format ", new JSONObject());
			jsonSchedulePayloadRoot.getJSONObject("format ").put("type", "pdf");
			jsonSchedulePayloadRoot.put("destination ", new JSONObject());
			jsonSchedulePayloadRoot.getJSONObject("destination ").put("inbox", new JSONObject());
			jsonSchedulePayloadRoot.put("once", new JSONObject());
			jsonSchedulePayloadRoot.getJSONObject("once").put("retriesAllowed", RETRY_INTERVAL_TIME);
			jsonSchedulePayloadRoot.getJSONObject("once").put("retryIntervalInSeconds", RETRY_INTERVAL_SECONDS);
			jsonSchedulePayloadRoot.getJSONObject("once").put("startdate", convertDateToString(LocalDateTime.now()));
			jsonSchedulePayloadRoot.getJSONObject("once").put("enddate",
					convertDateToString("9999-12-31 23:59:59", "yyyy-MM-dd HH:mm:ss"));
			// System.out.println(jsonSchedulePayload.toString());
			jsonSchedulePayload.getJSONObject("schedule").put("parameters",
					jsonRseportParameters.getJSONArray("parameter"));
			// System.out.println(jsonSchedulePayload.toString());
			return jsonSchedulePayload;
		} catch (JSONException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return jsonSchedulePayload;
	}

	private Document setWebiPromptV1Xml(Document xmlRseportParameters) {
		// TODO Auto-generated method stub
		Document xmlSchedulePayload = new Document();
		// xmlSchedulePayload.set
		Element scheduleElement = new Element("schedule");
		xmlSchedulePayload.addContent(scheduleElement);
		// scheduleElement.addContent(new Element("schedule"));
		// scheduleElement = scheduleElement.getChild("schedule");
		Element nameElement = new Element("name");
		scheduleElement.addContent(nameElement);
		Element serverGroupElement = new Element("serverGroup");
		serverGroupElement.setAttribute("id", "0");
		serverGroupElement.setAttribute("required", "false");
		scheduleElement.addContent(serverGroupElement);
		nameElement.setText(this.DOC_NAME);
		Element onceElement = new Element("once");
		scheduleElement.addContent(onceElement);
		// xmlSchedulePayload.addContent(tempElement);
		onceElement.setAttribute("retriesAllowed", RETRY_INTERVAL_TIME.toString());
		onceElement.setAttribute("retryIntervalInSeconds", RETRY_INTERVAL_SECONDS.toString());
		onceElement.addContent(new Element("startdate"));
		onceElement.addContent(new Element("enddate"));
		onceElement.getChild("startdate").setText(convertDateToString(LocalDateTime.now()));
		onceElement.getChild("enddate").setText(convertDateToString("9999-12-31 23:59:59", "yyyy-MM-dd HH:mm:ss"));
		scheduleElement.addContent(xmlRseportParameters.detachRootElement());
		// System.out.println(jsonSchedulePayload.toString());
		// xmlSchedulePayload.adoptNode(xmlRseportParameters.getDocumentElement());
		// System.out.println(jsonSchedulePayload.toString());
		// System.out.println(getXMLString(xmlSchedulePayload));
		Element formatElement = new Element("format");
		scheduleElement.addContent(formatElement);
		formatElement.setAttribute("type", "webi");
		if (formatElement.getAttribute("type").getValue().equals("csv")) {
			Element propertiesElement = new Element("properties");
			formatElement.addContent(propertiesElement);
			Element propertyElement = new Element("property");
			propertyElement.setAttribute("key", "textQualifier");
			propertyElement.setText("\"");
			propertiesElement.addContent(propertyElement);
			propertyElement = new Element("property");
			propertyElement.setAttribute("key", "columnDelimiter");
			propertyElement.setText(",");
			propertiesElement.addContent(propertyElement);
			propertyElement = new Element("property");
			propertyElement.setAttribute("key", "charset");
			propertyElement.setText("UTF-8");
			propertiesElement.addContent(propertyElement);
			propertyElement = new Element("property");
			propertyElement.setAttribute("key", "onePerDataProvider");
			propertyElement.setText("false");
			propertiesElement.addContent(propertyElement);
		}
		Element destinationElement = new Element("destination");
		scheduleElement.addContent(destinationElement);
		Element inboxElement = new Element("inbox");
		destinationElement.addContent(inboxElement);
		// formatElement.setText("webi");
		return xmlSchedulePayload;
	}

	private Document getRESTFulXml(String strURI) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		// HttpClientBuilder httpclientBuilder = HttpClients.custom();
		// httpclientBuilder.setDefaultHeaders(defaultHeaders);
		HttpGet httpGet = new HttpGet(strURI);
		httpGet.addHeader("content-type", "application/xml");
		httpGet.addHeader("accept", "application/xml");
		if (this.LOGONTOKEN != null && !this.LOGONTOKEN.isEmpty()) {
			httpGet.addHeader("X-SAP-LogonToken", this.LOGONTOKEN);
		}
		CloseableHttpResponse response1 = null;
		try {
			response1 = httpclient.execute(httpGet);
			// System.out.println(response1.getStatusLine());
			HttpEntity entity1 = response1.getEntity();
			// do something useful with the response body
			// and ensure it is fully consumed

			// EntityUtils.consume(entity1);
			try {
				String strData = EntityUtils.toString(entity1);
				Document xmlResponse = getXMLDocument(strData);
				if (response1.containsHeader("logonToken")) {
					Element newElement = new Element("logonToken");
					newElement.setText(response1.getHeaders("logonToken")[0].getValue());
					xmlResponse.addContent(newElement);
					// response1.getHeaders("logonToken")[0]);
					return xmlResponse;

				} else if (response1.containsHeader("X-SAP-LogonToken")) {
					// xmlResponse.put("logonToken",
					// response1.getHeaders("X-SAP-LogonToken")[0]);
					Element newElement = new Element("logonToken");
					newElement.addContent(response1.getHeaders("X-SAP-LogonToken")[0].getValue());
					xmlResponse.addContent(newElement);
					return xmlResponse;
				} else {
					return xmlResponse;
				}
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			System.out.println("Error on URL:" + strURI);
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println("Error on URL:" + strURI);
			e.printStackTrace();
		} finally {
			try {
				response1.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		}
		return null;
	}

	private JSONObject getRESTFul(String strURI) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		// HttpClientBuilder httpclientBuilder = HttpClients.custom();
		// httpclientBuilder.setDefaultHeaders(defaultHeaders);
		HttpGet httpGet = new HttpGet(strURI);
		httpGet.addHeader("content-type", "application/json");
		httpGet.addHeader("accept", "application/json");
		if (this.LOGONTOKEN != null && !this.LOGONTOKEN.isEmpty()) {
			httpGet.addHeader("X-SAP-LogonToken", this.LOGONTOKEN);
		}
		CloseableHttpResponse response1 = null;
		try {
			response1 = httpclient.execute(httpGet);
			// System.out.println(response1.getStatusLine());
			HttpEntity entity1 = response1.getEntity();
			// do something useful with the response body
			// and ensure it is fully consumed

			// EntityUtils.consume(entity1);
			try {
				String strData = EntityUtils.toString(entity1);
				JSONObject jsonResponse = new JSONObject();
				if (response1.containsHeader("logonToken")) {
					jsonResponse.put("logonToken", response1.getHeaders("logonToken")[0]);
					return jsonResponse;

				} else if (response1.containsHeader("X-SAP-LogonToken")) {
					jsonResponse.put("logonToken", response1.getHeaders("X-SAP-LogonToken")[0]);
					return jsonResponse;
				} else {
					return new JSONObject(strData);
				}
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			System.out.println("Error on URL:" + strURI);
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			System.out.println("Error on URL:" + strURI);
			e.printStackTrace();
		} finally {
			try {
				response1.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		}
		return null;
	}

	private JSONObject postRESTFul(String strURI, JSONObject sjonData) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost(strURI);
		httpPost.addHeader("content-type", "application/json");
		httpPost.addHeader("accept", "application/json");
		if (this.LOGONTOKEN != null && !this.LOGONTOKEN.isEmpty()) {
			httpPost.addHeader("X-SAP-LogonToken", this.LOGONTOKEN);
		}

		CloseableHttpResponse response1 = null;
		try {
			HttpEntity entity1 = null;
			if (sjonData != null) {
				entity1 = new StringEntity(sjonData.toString());
				httpPost.setEntity(entity1);
			}
			response1 = httpclient.execute(httpPost);
			// System.out.println(response1.getStatusLine());

			entity1 = response1.getEntity();
			// do something useful with the response body
			// and ensure it is fully consumed

			// EntityUtils.consume(entity1);
			try {
				if (response1.getHeaders("content-type")[0].toString().contains("json")) {
					return new JSONObject(EntityUtils.toString(entity1));
				} else
					return new JSONObject();
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				System.out.println("Error on URL:" + strURI);
				e.printStackTrace();
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				System.out.println("Error on URL:" + strURI);
				e.printStackTrace();
			}

		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				response1.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		}
		return null;
	}

	private Document postRESTFulXml(String strURI, Document xmlDoc) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpPost httpPost = new HttpPost(strURI);
		httpPost.addHeader("content-type", "application/xml");
		httpPost.addHeader("accept", "application/xml");
		if (this.LOGONTOKEN != null && !this.LOGONTOKEN.isEmpty()) {
			httpPost.addHeader("X-SAP-LogonToken", this.LOGONTOKEN);
		}

		CloseableHttpResponse response1 = null;
		try {
			HttpEntity entity1 = null;
			if (xmlDoc != null) {
				entity1 = new StringEntity(getXMLString(xmlDoc));
				httpPost.setEntity(entity1);
			}
			response1 = httpclient.execute(httpPost);
			// System.out.println(response1.getStatusLine());

			entity1 = response1.getEntity();
			// do something useful with the response body
			// and ensure it is fully consumed

			// EntityUtils.consume(entity1);
			try {
				if (response1.getHeaders("content-type")[0].toString().contains("xml")) {
					return getXMLDocument(EntityUtils.toString(entity1));
				} else {
					return getXMLDocument(null);
				}
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				System.out.println("Error on URL:" + strURI);
				e.printStackTrace();
			}

		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				response1.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		}
		return null;
	}

	private Document putRESTFulXml(String strURI, Document xmlDoc) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpPut httpPut = new HttpPut(strURI);
		httpPut.addHeader("content-type", "application/xml");
		httpPut.addHeader("accept", "application/xml");
		if (this.LOGONTOKEN != null && !this.LOGONTOKEN.isEmpty()) {
			httpPut.addHeader("X-SAP-LogonToken", this.LOGONTOKEN);
		}

		CloseableHttpResponse response1 = null;
		try {
			HttpEntity entity1 = null;
			if (xmlDoc != null) {
				entity1 = new StringEntity(getXMLString(xmlDoc));
				httpPut.setEntity(entity1);
			}
			response1 = httpclient.execute(httpPut);
			// System.out.println(response1.getStatusLine());

			entity1 = response1.getEntity();
			// do something useful with the response body
			// and ensure it is fully consumed

			// EntityUtils.consume(entity1);
			try {
				if (response1.getHeaders("content-type")[0].toString().contains("xml")) {
					return getXMLDocument(EntityUtils.toString(entity1));
				} else {
					return getXMLDocument(null);
				}
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				System.out.println("Error on URL:" + strURI);
				e.printStackTrace();
			}

		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				response1.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		}
		return null;
	}

	private JSONObject putRESTFul(String strURI, JSONObject sjonData) {
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpPut httpPut = new HttpPut(strURI);
		httpPut.addHeader("content-type", "application/json");
		httpPut.addHeader("accept", "application/json");
		if (this.LOGONTOKEN != null && !this.LOGONTOKEN.isEmpty()) {
			httpPut.addHeader("X-SAP-LogonToken", this.LOGONTOKEN);
		}

		CloseableHttpResponse response1 = null;
		try {
			HttpEntity entity1 = null;
			if (sjonData != null) {
				entity1 = new StringEntity(sjonData.toString());
				httpPut.setEntity(entity1);
			}
			response1 = httpclient.execute(httpPut);
			// System.out.println(response1.getStatusLine());

			entity1 = response1.getEntity();
			// do something useful with the response body
			// and ensure it is fully consumed

			// EntityUtils.consume(entity1);
			try {
				if (response1.getHeaders("content-type")[0].toString().contains("json")) {
					return new JSONObject(EntityUtils.toString(entity1));
				} else
					return new JSONObject();
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				System.out.println("Error on URL:" + strURI);
				e.printStackTrace();
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				System.out.println("Error on URL:" + strURI);
				e.printStackTrace();
			}

		} catch (ClientProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			try {
				response1.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
		}
		return null;
	}

	/**
	 * To convert XML Text to DOM Document
	 * 
	 * @param xmlString
	 *            Text formats as text
	 * @return DOM Document Object
	 * 
	 */
	private static Document getXMLDocument(String xmlString) {

		SAXBuilder builder = new SAXBuilder(XMLReaders.NONVALIDATING);
		StringReader stringReader;
		// DocumentBuilderFactory factory =
		// DocumentBuilderFactory.newInstance();
		// DocumentBuilder builder;
		try {
			// builder = factory.newDocumentBuilder();

			org.jdom2.Document document = null;
			stringReader = new StringReader(xmlString);
			if (xmlString == null || xmlString == "") {
				document = builder.build(stringReader);
			} else {
				stringReader = new StringReader(xmlString);
				// InputSource inputSource = new InputSource(stringReader);
				// InputStream inputStream = new
				// ByteArrayInputStream(xmlString.getBytes());
				// inputSource.setEncoding("utf-8");
				document = builder.build(stringReader);
			}
			return document;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * To convert DOM Document to XML Text
	 * 
	 * @param doc
	 *            DOM Document
	 * @return XML Text
	 * 
	 */
	private static String getXMLString(Document doc) {
		try {
			Format xmlFormat = org.jdom2.output.Format.getPrettyFormat();
			xmlFormat.setOmitDeclaration(true);
			XMLOutputter xmOut = new XMLOutputter(xmlFormat);

			return xmOut.outputString(doc);

		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
