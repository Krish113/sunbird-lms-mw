package org.sunbird.metrics.actors;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LoggerEnum;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.ProjectUtil.EsIndex;
import org.sunbird.common.models.util.ProjectUtil.EsType;
import org.sunbird.common.models.util.ProjectUtil.ReportTrackingStatus;
import org.sunbird.common.models.util.datasecurity.DecryptionService;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.ActorUtil;
import org.sunbird.learner.util.Util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OrganisationMetricsActor extends BaseMetricsActor {

  private static ObjectMapper mapper = new ObjectMapper();
  private static final String view = "org";
  private CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private Util.DbInfo reportTrackingdbInfo = Util.dbInfoMap.get(JsonKey.REPORT_TRACKING_DB);
  DecryptionService decryptionService =
      org.sunbird.common.models.util.datasecurity.impl.ServiceFactory
          .getDecryptionServiceInstance(null);

  @Override
  public void onReceive(Object message) throws Throwable {
    if (message instanceof Request) {
      try {
        ProjectLogger.log("OrganisationMetricsActor-onReceive called");
        Request actorMessage = (Request) message;
        if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.ORG_CREATION_METRICS.getValue())) {
          orgCreationMetrics(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.ORG_CONSUMPTION_METRICS.getValue())) {
          orgConsumptionMetrics(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.ORG_CREATION_METRICS_REPORT.getValue())) {
          orgCreationMetricsReport(actorMessage);
        } else if (actorMessage.getOperation()
            .equalsIgnoreCase(ActorOperations.ORG_CONSUMPTION_METRICS_REPORT.getValue())) {
          orgConsumptionMetricsReport(actorMessage);
        } else {
          ProjectLogger.log("UNSUPPORTED OPERATION", LoggerEnum.INFO.name());
          ProjectCommonException exception =
              new ProjectCommonException(ResponseCode.invalidOperationName.getErrorCode(),
                  ResponseCode.invalidOperationName.getErrorMessage(),
                  ResponseCode.CLIENT_ERROR.getResponseCode());
          sender().tell(exception, self());
        }
      } catch (Exception ex) {
        ProjectLogger.log(ex.getMessage(), ex);
        sender().tell(ex, self());
      }
    } else {
      // Throw exception as message body
      ProjectLogger.log("UNSUPPORTED MESSAGE");
      ProjectCommonException exception =
          new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
              ResponseCode.invalidRequestData.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
    }
  }

  private void orgConsumptionMetricsReport(Request actorMessage) {
    ProjectLogger.log("OrganisationMetricsActor-orgConsumptionMetricsReport called");
    String requestId = createReportTrackingEntry(actorMessage);

    Response response = new Response();
    response.put(JsonKey.REQUEST_ID, requestId);
    sender().tell(response, self());

    // assign the back ground task to background job actor ...
    Request backGroundRequest = new Request();
    backGroundRequest.setOperation(ActorOperations.PROCESS_DATA.getValue());

    Map<String, Object> innerMap = new HashMap<>();
    innerMap.put(JsonKey.REQUEST_ID, requestId);
    innerMap.put(JsonKey.REQUEST, JsonKey.OrgConsumption);

    backGroundRequest.setRequest(innerMap);
    ActorUtil.tell(backGroundRequest);
    return;
  }

  private String createReportTrackingEntry(Request actorMessage) {
    ProjectLogger.log("Create Report Tracking Entry");
    SimpleDateFormat simpleDateFormat= ProjectUtil.getDateFormatter();
    String requestedBy = (String) actorMessage.get(JsonKey.REQUESTED_BY);
    String orgId = (String) actorMessage.get(JsonKey.ORG_ID);
    String period = (String) actorMessage.get(JsonKey.PERIOD);

    Map<String, Object> requestedByInfo = ElasticSearchUtil.getDataByIdentifier(
        EsIndex.sunbird.getIndexName(), EsType.user.getTypeName(), requestedBy);
    if (ProjectUtil.isNull(requestedByInfo)
        || ProjectUtil.isStringNullOREmpty((String) requestedByInfo.get(JsonKey.FIRST_NAME))) {
      throw new ProjectCommonException(ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    Map<String, Object> orgData = validateOrg(orgId);
    if (null == orgData) {
      throw new ProjectCommonException(ResponseCode.invalidOrgData.getErrorCode(),
          ResponseCode.invalidOrgData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
    }

    String requestId = ProjectUtil.getUniqueIdFromTimestamp(1);

    Map<String, Object> requestDbInfo = new HashMap<>();
    requestDbInfo.put(JsonKey.ID, requestId);
    requestDbInfo.put(JsonKey.USER_ID, requestedBy);
    requestDbInfo.put(JsonKey.FIRST_NAME, requestedByInfo.get(JsonKey.FIRST_NAME));
    requestDbInfo.put(JsonKey.STATUS, ReportTrackingStatus.NEW.getValue());
    requestDbInfo.put(JsonKey.RESOURCE_ID, orgId);
    requestDbInfo.put(JsonKey.PERIOD, period);
    requestDbInfo.put(JsonKey.CREATED_DATE, simpleDateFormat.format(new Date()));
    requestDbInfo.put(JsonKey.UPDATED_DATE, simpleDateFormat.format(new Date()));
    String decryptedEmail =
        decryptionService.decryptData((String) requestedByInfo.get(JsonKey.ENC_EMAIL));
    requestDbInfo.put(JsonKey.EMAIL, decryptedEmail);
    requestDbInfo.put(JsonKey.FORMAT, actorMessage.get(JsonKey.FORMAT));

    cassandraOperation.insertRecord(reportTrackingdbInfo.getKeySpace(),
        reportTrackingdbInfo.getTableName(), requestDbInfo);

    return requestId;
  }

  private void orgCreationMetricsReport(Request actorMessage) {
    ProjectLogger.log("OrganisationMetricsActor-orgCreationMetricsReport called");
    String requestId = createReportTrackingEntry(actorMessage);
    Response response = new Response();
    response.put(JsonKey.REQUEST_ID, requestId);
    sender().tell(response, self());

    // assign the back ground task to background job actor ...
    Request backGroundRequest = new Request();
    backGroundRequest.setOperation(ActorOperations.PROCESS_DATA.getValue());

    Map<String, Object> innerMap = new HashMap<>();
    innerMap.put(JsonKey.REQUEST_ID, requestId);
    innerMap.put(JsonKey.REQUEST, JsonKey.OrgCreation);
    backGroundRequest.setRequest(innerMap);
    ActorUtil.tell(backGroundRequest);
    return;
  }

  protected Map<String, Object> getViewData(String orgId, Object orgName) {
    Map<String, Object> orgData = new HashMap<>();
    Map<String, Object> viewData = new HashMap<>();
    orgData.put(JsonKey.ORG_ID, orgId);
    orgData.put(JsonKey.ORG_NAME, orgName);
    viewData.put(view, orgData);
    return viewData;
  }

  private String getQueryRequest(String periodStr, String orgId, String operation) {
    ProjectLogger.log("orgId " + orgId);
    Map<String, Object> dateMap = getStartAndEndDate(periodStr);
    Map<String, String> operationMap = new LinkedHashMap<>();
    ProjectLogger.log("period" + dateMap);
    switch (operation) {
      case "Create": {
        operationMap.put("dateKey", "createdOn");
        operationMap.put("status", OrganisationMetricsUtil.ContentStatus.Draft.name());
        operationMap.put("userActionKey", "createdBy");
        operationMap.put("contentCount", "required");
        break;
      }
      case "Review": {
        operationMap.put("dateKey", "lastSubmittedOn");
        operationMap.put("status", OrganisationMetricsUtil.ContentStatus.Review.name());
        break;
      }
      case "Publish": {
        operationMap.put("dateKey", "lastPublishedOn");
        operationMap.put("status", OrganisationMetricsUtil.ContentStatus.Live.name());
        operationMap.put("userActionKey", "lastPublishedBy");
        break;
      }
    }
    StringBuilder builder = new StringBuilder();
    builder.append("{\"request\":{\"rawQuery\":{\"query\":{\"filtered\":")
        .append("{\"query\":{\"bool\":{\"must\":[{\"query\":{\"range\":{\"")
        .append(operationMap.get("dateKey")).append("\":{\"gte\":\"")
        .append(dateMap.get(startDate) + "\",\"lte\":\"" + dateMap.get(endDate) + "\"}}}}")
        .append(",{\"bool\":{\"should\":[{\"match\":{\"contentType.raw\":\"Story\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"Worksheet\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"Game\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"Collection\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"TextBook\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"TextBookUnit\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"Course\"}}")
        .append(",{\"match\":{\"contentType.raw\":\"CourseUnit\"}}]}},")
        .append("{\"match\":{\"createdFor.raw\":\"" + orgId + "\"}}")
        .append(",{\"match\":{\"status.raw\":\"" + operationMap.get("status"))
        .append("\"}}]}}}},\"aggs\":{\"");
    if (operationMap.containsValue("createdOn")) {
      builder.append(operationMap.get("dateKey") + "\":{\"date_histogram\":{\"field\":\"")
          .append(operationMap.get("dateKey"))
          .append("\",\"interval\":\"" + dateMap.get(INTERVAL) + "\",\"format\":\"")
          .append(dateMap.get(FORMAT) + "\"")
          .append(",\"time_zone\":\"+05:30\",\"extended_bounds\":{\"min\":")
          .append(dateMap.get(startTimeMilis) + ",\"max\":")
          .append(dateMap.get(endTimeMilis) + "}}},\"");
    }
    builder.append("status\":{\"terms\":{\"field\":\"status.raw\",\"include\":[\"")
        .append(operationMap.get("status").toLowerCase() + "\"]},\"aggs\":{\"")
        .append(operationMap.get("dateKey") + "\":{\"date_histogram\":{\"field\":\"")
        .append(operationMap.get("dateKey") + "\",\"interval\":\"" + dateMap.get(INTERVAL))
        .append("\",\"format\":\"" + dateMap.get(FORMAT))
        .append("\",\"time_zone\":\"+05:30\",\"extended_bounds\":{\"min\":")
        .append(dateMap.get(startTimeMilis) + ",\"max\":").append(dateMap.get(endTimeMilis))
        .append("}}}}}");
    if (operationMap.containsKey("userActionKey")) {
      builder.append(",\"" + operationMap.get("userActionKey") + ".count\":")
          .append("{\"cardinality\":{\"field\":\"" + operationMap.get("userActionKey"))
          .append(".raw\",\"precision_threshold\":100}}");
    }

    if (operationMap.containsKey("contentCount")) {
      builder.append(",\"content_count\":{\"terms\":{\"field\":\"contentType.raw\"")
          .append(",\"exclude\":[\"assets\",\"plugin\",\"template\"]}},")
          .append("\"total_content_count\":{\"sum_bucket\":")
          .append("{\"buckets_path\":\"content_count>_count\"}}");
    }
    builder.append("}}}}");
    return builder.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> putAggregationMap(String responseStr,
      Map<String, Object> aggregationMap, String operation) {
    try {
      Map<String, Object> resultData = mapper.readValue(responseStr, Map.class);
      resultData = (Map<String, Object>) resultData.get(JsonKey.RESULT);
      resultData = (Map<String, Object>) resultData.get(JsonKey.AGGREGATIONS);
      List<Map<String, Object>> statusList = new ArrayList<>();
      for (Map.Entry<String, Object> data : resultData.entrySet()) {
        if ("status".equalsIgnoreCase(data.getKey())) {
          Map<String, Object> statusMap = new HashMap<>();
          statusMap.put(operation, data.getValue());
          statusList = (List<Map<String, Object>>) aggregationMap.get(data.getKey());
          if (null == statusList) {
            statusList = new ArrayList<>();
          }
          statusList.add(statusMap);
          aggregationMap.put(data.getKey(), statusList);
        } else {
          aggregationMap.put(data.getKey(), data.getValue());
        }
      }
    } catch (Exception e) {
      ProjectLogger.log(e.getMessage(), e);
    }
    return aggregationMap;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private String orgCreationResponseGenerator(String periodStr, Map<String, Object> resultData) {
    // String dataSet = "creation";
    String result = null;
    Map<String, Object> responseMap = new HashMap<>();
    try {
      Map<String, Object> snapshot = new LinkedHashMap<>();
      Map<String, Object> dataMap = new HashMap<>();
      dataMap.put(JsonKey.NAME, "Number of contents created");
      Map<String, Object> contentData = (Map<String, Object>) resultData.get("total_content_count");
      if (null != contentData && !contentData.isEmpty()) {
        dataMap.put(VALUE, contentData.get(VALUE));
      } else {
        dataMap.put(VALUE, 0);
      }
      snapshot.put("org.creation.content.count", dataMap);
      dataMap = new HashMap<>();
      dataMap.put(JsonKey.NAME, "Number of authors");
      if (null != resultData.get("createdBy.count")) {
        dataMap.putAll((Map<String, Object>) resultData.get("createdBy.count"));
      } else {
        dataMap.put(VALUE, 0);
      }
      snapshot.put("org.creation.authors.count", dataMap);
      dataMap = new HashMap<>();
      dataMap.put(JsonKey.NAME, "Number of reviewers");
      if (null != resultData.get("lastPublishedBy.count")) {
        dataMap.putAll((Map<String, Object>) resultData.get("lastPublishedBy.count"));
      } else {
        dataMap.put(VALUE, 0);
      }
      snapshot.put("org.creation.reviewers.count", dataMap);
      dataMap = new HashMap<>();
      Object value = null;
      List<Map<String, Object>> valueMapList = (List<Map<String, Object>>) resultData.get("status");
      Map<String, Object> statusValueMap = new HashMap<>();
      statusValueMap.put("live", 0);
      statusValueMap.put("draft", 0);
      statusValueMap.put("review", 0);
      for (Map<String, Object> data : valueMapList) {
        Map<String, Object> statusMap = new HashMap<>();
        List<Map<String, Object>> valueList = new ArrayList<>();
        if (data.containsKey("Create")) {
          statusMap = (Map<String, Object>) data.get("Create");
          valueList = (List<Map<String, Object>>) statusMap.get("buckets");
          if (!valueList.isEmpty()) {
            statusMap = valueList.get(0);
            statusValueMap.put("draft", statusMap.get("doc_count"));
            statusValueMap.put("draftBucket", statusMap.get("createdOn"));
          }
        } else if (data.containsKey("Publish")) {
          statusMap = (Map<String, Object>) data.get("Publish");
          valueList = (List<Map<String, Object>>) statusMap.get("buckets");
          if (!valueList.isEmpty()) {
            statusMap = valueList.get(0);
            statusValueMap.put("live", statusMap.get("doc_count"));
            statusValueMap.put("liveBucket", statusMap.get("lastPublishedOn"));
          }
        } else if (data.containsKey("Review")) {
          statusMap = (Map<String, Object>) data.get("Review");
          valueList = (List<Map<String, Object>>) statusMap.get("buckets");
          if (!valueList.isEmpty()) {
            statusMap = valueList.get(0);
            statusValueMap.put("review", statusMap.get("doc_count"));
            statusValueMap.put("reviewBucket", statusMap.get("lastSubmittedOn"));
          }
        }
      }

      dataMap.put(JsonKey.NAME, "Number of content items created");
      value = statusValueMap.get("draft");
      dataMap.put(VALUE, value);
      snapshot.put("org.creation.content[@status=draft].count", dataMap);
      dataMap = new HashMap<>();
      dataMap.put(JsonKey.NAME, "Number of content items reviewed");
      value = statusValueMap.get("review");
      dataMap.put(VALUE, value);
      snapshot.put("org.creation.content[@status=review].count", dataMap);
      dataMap = new HashMap<>();
      value = statusValueMap.get("live");
      dataMap.put(JsonKey.NAME, "Number of content items published");
      dataMap.put(VALUE, value);
      snapshot.put("org.creation.content[@status=published].count", dataMap);

      Map<String, Object> series = new LinkedHashMap<>();
      // Map aggKeyMap = (Map) resultData.get("createdOn");
      // List<Map<String, Object>> bucket = getBucketData(aggKeyMap, periodStr);
      Map<String, Object> seriesData = new LinkedHashMap<>();
      /*
       * if ("5w".equalsIgnoreCase(periodStr)) { seriesData.put(JsonKey.NAME,
       * "Content created per week"); } else { seriesData.put(JsonKey.NAME,
       * "Content created per day"); } seriesData.put(JsonKey.SPLIT, "content.created_on");
       * seriesData.put(GROUP_ID, "org.content.count"); if (null == bucket || bucket.isEmpty()) {
       * bucket = createBucketStructure(periodStr); } seriesData.put("buckets", bucket);
       * series.put("org.creation.content.created_on.count", seriesData);
       */

      Map<String, Object> statusList = new HashMap();
      List<Map<String, Object>> statusBucket = new ArrayList<>();
      statusList = (Map<String, Object>) statusValueMap.get("draftBucket");
      statusBucket = getBucketData(statusList, periodStr);
      if (null == statusBucket || statusBucket.isEmpty()) {
        statusBucket = createBucketStructure(periodStr);
      }
      seriesData = new LinkedHashMap<>();
      seriesData.put(JsonKey.NAME, "Draft");
      seriesData.put(JsonKey.SPLIT, "content.created_on");
      seriesData.put(GROUP_ID, "org.content.count");
      seriesData.put("buckets", statusBucket);
      series.put("org.creation.content[@status=draft].count", seriesData);

      statusList = (Map<String, Object>) statusValueMap.get("reviewBucket");
      statusBucket = getBucketData(statusList, periodStr);
      if (null == statusBucket || statusBucket.isEmpty()) {
        statusBucket = createBucketStructure(periodStr);
      }
      seriesData = new LinkedHashMap<>();
      seriesData.put(JsonKey.NAME, "Review");
      seriesData.put(JsonKey.SPLIT, "content.reviewed_on");
      seriesData.put(GROUP_ID, "org.content.count");
      seriesData.put("buckets", statusBucket);
      series.put("org.creation.content[@status=review].count", seriesData);

      statusList = (Map<String, Object>) statusValueMap.get("liveBucket");
      statusBucket = getBucketData(statusList, periodStr);
      if (null == statusBucket || statusBucket.isEmpty()) {
        statusBucket = createBucketStructure(periodStr);
      }
      seriesData = new LinkedHashMap<>();
      seriesData.put(JsonKey.NAME, "Live");
      seriesData.put(JsonKey.SPLIT, "content.published_on");
      seriesData.put(GROUP_ID, "org.content.count");
      seriesData.put("buckets", statusBucket);
      series.put("org.creation.content[@status=published].count", seriesData);

      responseMap.put(JsonKey.SNAPSHOT, snapshot);
      responseMap.put(JsonKey.SERIES, series);

      result = mapper.writeValueAsString(responseMap);
    } catch (JsonProcessingException e) {
      ProjectLogger.log("Error occured", e);
    }
    return result;
  }


  @SuppressWarnings("unchecked")
  private void orgCreationMetrics(Request actorMessage) {
    ProjectLogger.log("In orgCreationMetrics api");
    try {
      String periodStr = (String) actorMessage.getRequest().get(JsonKey.PERIOD);
      String orgId = (String) actorMessage.getRequest().get(JsonKey.ORG_ID);
      Map<String, Object> orgData = validateOrg(orgId);
      if (null == orgData) {
        ProjectCommonException exception =
            new ProjectCommonException(ResponseCode.invalidOrgData.getErrorCode(),
                ResponseCode.invalidOrgData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }
      String orgName = (String) orgData.get(JsonKey.ORG_NAME);
      if (ProjectUtil.isStringNullOREmpty(orgName)) {
        ProjectCommonException exception =
            new ProjectCommonException(ResponseCode.invalidOrgData.getErrorCode(),
                ResponseCode.invalidOrgData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }

      // Map<String, Object> aggregationMap = getOrgCreationData(periodStr, orgId);
      Map<String, Object> aggregationMap =
          (Map<String, Object>) cache.getData(JsonKey.OrgCreation, orgId, periodStr);
      if (null == aggregationMap || aggregationMap.isEmpty()) {
        aggregationMap = getOrgCreationData(periodStr, orgId);
        cache.setData(JsonKey.OrgCreation, orgId, periodStr, aggregationMap);
      }
      String responseFormat = orgCreationResponseGenerator(periodStr, aggregationMap);
      Response response =
          metricsResponseGenerator(responseFormat, periodStr, getViewData(orgId, orgName));
      sender().tell(response, self());
    } catch (ProjectCommonException e) {
      ProjectLogger.log("Some error occurs", e);
      sender().tell(e, self());
      return;
    } catch (Exception e) {
      ProjectLogger.log("Some error occurs", e);
      throw new ProjectCommonException(ResponseCode.internalError.getErrorCode(),
          ResponseCode.internalError.getErrorMessage(),
          ResponseCode.SERVER_ERROR.getResponseCode());
    }
  }

  private Map<String, Object> getOrgCreationData(String periodStr, String orgId) throws Exception {
    Map<String, Object> aggregationMap = new HashMap<>();
    for (String operation : OrganisationMetricsUtil.operationList) {
      String request = getQueryRequest(periodStr, orgId, operation);
      String esResponse = makePostRequest(JsonKey.EKSTEP_ES_METRICS_API_URL, request);
      // String esResponse = getDataFromEkstep(request, JsonKey.EKSTEP_ES_METRICS_URL);
      aggregationMap = putAggregationMap(esResponse, aggregationMap, operation);
    }
    return aggregationMap;
  }

  private void orgConsumptionMetrics(Request actorMessage) {
    ProjectLogger.log("In orgConsumptionMetrics api");
    try {
      String periodStr = (String) actorMessage.getRequest().get(JsonKey.PERIOD);
      String orgId = (String) actorMessage.getRequest().get(JsonKey.ORG_ID);
      Map<String, Object> orgData = validateOrg(orgId);
      if (null == orgData) {
        ProjectCommonException exception =
            new ProjectCommonException(ResponseCode.invalidOrgData.getErrorCode(),
                ResponseCode.invalidOrgData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }
      String orgName = (String) orgData.get(JsonKey.ORG_NAME);
      String orgHashId = (String) orgData.get(JsonKey.HASHTAGID);
      ProjectLogger.log("orgId" + orgHashId);
      if (ProjectUtil.isStringNullOREmpty(orgName)) {
        ProjectCommonException exception =
            new ProjectCommonException(ResponseCode.invalidOrgData.getErrorCode(),
                ResponseCode.invalidOrgData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }
      if (ProjectUtil.isStringNullOREmpty(orgHashId)) {
        orgHashId = orgId;
      }
      String orgRootId = (String) orgData.get(JsonKey.ROOT_ORG_ID);
      if (ProjectUtil.isStringNullOREmpty(orgRootId)) {
        orgRootId = orgId;
      }
      ProjectLogger.log("RootOrgId " + orgRootId);
      Map<String, Object> rootOrgData = validateOrg(orgRootId);
      if (null == rootOrgData || rootOrgData.isEmpty()) {
        ProjectCommonException exception =
            new ProjectCommonException(ResponseCode.invalidRootOrgData.getErrorCode(),
                ResponseCode.invalidRootOrgData.getErrorMessage(),
                ResponseCode.CLIENT_ERROR.getResponseCode());
        sender().tell(exception, self());
        return;
      }
      String channel = (String) rootOrgData.get(JsonKey.HASHTAGID);
      ProjectLogger.log("channel" + channel);
      // String responseFormat = getOrgConsumptionData(actorMessage, periodStr, orgHashId, channel);
      String responseFormat = (String) cache.getData(JsonKey.OrgConsumption, orgId, periodStr);
      if (ProjectUtil.isStringNullOREmpty(responseFormat)) {
        responseFormat = getOrgConsumptionData(actorMessage, periodStr, orgHashId, channel);
        ProjectLogger.log("Response"+responseFormat);
        cache.setData(JsonKey.OrgConsumption, orgId, periodStr, responseFormat);
      }
      Response response =
          metricsResponseGenerator(responseFormat, periodStr, getViewData(orgId, orgName));
      sender().tell(response, self());
    } catch (ProjectCommonException e) {
      ProjectLogger.log("Some error occurs", e);
      sender().tell(e, self());
      return;
    } catch (Exception e) {
      ProjectLogger.log("Some error occurs", e);
      throw new ProjectCommonException(ResponseCode.internalError.getErrorCode(),
          ResponseCode.internalError.getErrorMessage(),
          ResponseCode.SERVER_ERROR.getResponseCode());
    }
  }

  private String getOrgConsumptionData(Request actorMessage, String periodStr, String orgHashId,
      String channel) throws JsonProcessingException, Exception {
    String requestStr = getOrgMetricsRequest(actorMessage, periodStr, orgHashId, null, channel);
    String ekStepResponse = makePostRequest(JsonKey.EKSTEP_METRICS_API_URL, requestStr);
    String responseFormat = orgConsumptionResponseGenerator(periodStr, ekStepResponse);
    return responseFormat;
  }

  private String getOrgMetricsRequest(Request actorMessage, String periodStr, String orgHashId,
      String userId, String channel) throws JsonProcessingException {
    Request request = new Request();
    request.setId(actorMessage.getId());
    Map<String, Object> requestObject = new HashMap<>();
    requestObject.put(JsonKey.PERIOD, getEkstepPeriod(periodStr));
    Map<String, Object> filterMap = new HashMap<>();
    filterMap.put(JsonKey.TAG, orgHashId);
    if (!ProjectUtil.isStringNullOREmpty(userId)) {
      filterMap.put(USER_ID, userId);
    }
    requestObject.put(JsonKey.FILTER, filterMap);
    requestObject.put(JsonKey.CHANNEL, channel);
    request.setRequest(requestObject);
    String requestStr = mapper.writeValueAsString(request);
    return requestStr;
  }

  @SuppressWarnings("unchecked")
  private String orgConsumptionResponseGenerator(String period, String ekstepResponse) {
    String result = "";
    try {
      Map<String, Object> resultData = mapper.readValue(ekstepResponse, Map.class);
      resultData = (Map<String, Object>) resultData.get(JsonKey.RESULT);
      List<Map<String, Object>> resultList =
          (List<Map<String, Object>>) resultData.get(JsonKey.METRICS);
      List<Map<String, Object>> userBucket = createBucketStructure(period);
      List<Map<String, Object>> consumptionBucket = createBucketStructure(period);
      Map<String, Object> userData = new HashMap<>();
      int index = 0;
      Collections.reverse(resultList);
      Map<String, Object> resData = new HashMap<>();
      for (Map<String, Object> res : resultList) {
        resData = consumptionBucket.get(index);
        userData = userBucket.get(index);
        String bucketDate = "";
        String metricsDate = "";
        if ("5w".equalsIgnoreCase(period)) {
          bucketDate = (String) resData.get("key");
          bucketDate = bucketDate.substring(bucketDate.length() - 2, bucketDate.length());
          metricsDate = String.valueOf(res.get("d_period"));
          metricsDate = metricsDate.substring(metricsDate.length() - 2, metricsDate.length());
        } else {
          bucketDate = (String) resData.get("key_name");
          metricsDate = String.valueOf(res.get("d_period"));
          Date date = new SimpleDateFormat("yyyyMMdd").parse(metricsDate);
          metricsDate = new SimpleDateFormat("yyyy-MM-dd").format(date);
        }
        if (metricsDate.equalsIgnoreCase(bucketDate)) {
          Double totalTimeSpent = (Double) res.get("m_total_ts");
          Integer totalUsers = (Integer) res.get("m_total_users_count");
          resData.put(VALUE, totalTimeSpent);
          userData.put(VALUE, totalUsers);
        }
        if (index < consumptionBucket.size() && index < userBucket.size()) {
          index++;
        }
      }

      Map<String, Object> series = new HashMap<>();

      Map<String, Object> seriesData = new LinkedHashMap<>();
      if ("5w".equalsIgnoreCase(period)) {
        seriesData.put(JsonKey.NAME, "Time spent by week");
      } else {
        seriesData.put(JsonKey.NAME, "Time spent by day");
      }
      seriesData.put(JsonKey.SPLIT, "content.time_spent.user.count");
      seriesData.put(JsonKey.TIME_UNIT, "seconds");
      seriesData.put(GROUP_ID, "org.timespent.sum");
      seriesData.put("buckets", consumptionBucket);
      series.put("org.consumption.content.time_spent.sum", seriesData);
      seriesData = new LinkedHashMap<>();
      if ("5w".equalsIgnoreCase(period)) {
        seriesData.put(JsonKey.NAME, "Number of users per week");
      } else {
        seriesData.put(JsonKey.NAME, "Number of users per day");
      }
      seriesData.put(JsonKey.SPLIT, "content.users.count");
      seriesData.put(GROUP_ID, "org.users.count");
      seriesData.put("buckets", userBucket);
      series.put("org.consumption.content.users.count", seriesData);

      resultData = (Map<String, Object>) resultData.get(JsonKey.SUMMARY);
      Map<String, Object> snapshot = new LinkedHashMap<>();
      Map<String, Object> dataMap = new HashMap<>();
      dataMap.put(JsonKey.NAME, "Number of visits by users");
      dataMap.put(VALUE, resultData.get("m_total_users_count"));
      snapshot.put("org.consumption.content.session.count", dataMap);
      dataMap = new LinkedHashMap<>();
      dataMap.put(JsonKey.NAME, "Content consumption time");
      dataMap.put(VALUE, resultData.get("m_total_ts"));
      dataMap.put(JsonKey.TIME_UNIT, "seconds");
      snapshot.put("org.consumption.content.time_spent.sum", dataMap);
      dataMap = new LinkedHashMap<>();
      dataMap.put(JsonKey.NAME, "Average time spent by user per visit");
      dataMap.put(VALUE, resultData.get("m_avg_ts_session"));
      dataMap.put(JsonKey.TIME_UNIT, "seconds");
      snapshot.put("org.consumption.content.time_spent.average", dataMap);
      Map<String, Object> responseMap = new HashMap<>();
      responseMap.put(JsonKey.SNAPSHOT, snapshot);
      responseMap.put(JsonKey.SERIES, series);

      result = mapper.writeValueAsString(responseMap);
    } catch (JsonProcessingException e) {
      ProjectLogger.log("Error occured", e);
    } catch (Exception e) {
      ProjectLogger.log("Error occured", e);
    }
    return result;
  }

  private Map<String, Object> validateOrg(String orgId) {
    try {
      Map<String, Object> result =
          ElasticSearchUtil.getDataByIdentifier(ProjectUtil.EsIndex.sunbird.getIndexName(),
              ProjectUtil.EsType.organisation.getTypeName(), orgId);
      if (null == result || result.isEmpty()) {
        return null;
      }
      ProjectLogger.log("Result:" + result.toString());
      return result;
    } catch (Exception e) {
      ProjectLogger.log("Error occured", e);
      throw new ProjectCommonException(ResponseCode.esError.getErrorCode(),
          ResponseCode.esError.getErrorMessage(), ResponseCode.SERVER_ERROR.getResponseCode());
    }
  }


}
