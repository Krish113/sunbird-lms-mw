package org.sunbird.metrics.actors;

import static akka.testkit.JavaTestKit.duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectUtil.EsIndex;
import org.sunbird.common.models.util.ProjectUtil.EsType;
import org.sunbird.common.request.Request;
import org.sunbird.helper.ServiceFactory;
import org.sunbird.learner.util.Util;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.TestActorRef;
import akka.testkit.javadsl.TestKit;

/**
 * Created by arvind on 8/9/17.
 */
public class CourseMetricsActorTest {

  static ActorSystem system;
  final static Props props = Props.create(CourseMetricsActor.class);
  static TestActorRef<CourseMetricsActor> ref;
  private static CassandraOperation cassandraOperation = ServiceFactory.getInstance();
  private static Util.DbInfo batchDbInfo = Util.dbInfoMap.get(JsonKey.COURSE_BATCH_DB);
  private static Util.DbInfo reportTrackingdbInfo = Util.dbInfoMap.get(JsonKey.REPORT_TRACKING_DB);
  static String userId = "dnk298voopir80249";
  static String courseId = "mclr309f39";
  static String batchId ="jkwf6t3r083fp4h";
  static final String orgId = "vdckcyigc68569";
  private static final String contentId = "nnci3u9f97mxcfu";

  @BeforeClass
  public static void setUp() {
    system = ActorSystem.create("system");
    ref = TestActorRef.create(system, props, "testActor");
    insertBatchDataToES();
    insertOrgDataToES();
    insertUserCoursesDataToES();
    insertUserDataToES();
  }

  private static void insertUserDataToES(){
    Map<String , Object> userMap = new HashMap<>();
    userMap.put(JsonKey.USER_ID , userId);
    userMap.put(JsonKey.FIRST_NAME , "alpha");
    userMap.put(JsonKey.ID , userId);
    userMap.put(JsonKey.ROOT_ORG_ID, "ORG_001");
    userMap.put(JsonKey.USERNAME , "alpha-beta");
    userMap.put(JsonKey.REGISTERED_ORG_ID, orgId);
    ElasticSearchUtil.createData(EsIndex.sunbird.getIndexName(),EsType.user.getTypeName() , userId , userMap);
  }

  private static void insertBatchDataToES(){
    Map<String , Object> batchMap = new HashMap<>();
    batchMap.put(JsonKey.ID , batchId);
    ElasticSearchUtil.createData(EsIndex.sunbird.getIndexName(),EsType.course.getTypeName() , batchId , batchMap);
  }

  private static void insertUserCoursesDataToES(){
    Map<String , Object> userCoursesMap = new HashMap<>();
    userCoursesMap.put(JsonKey.ID , batchId+JsonKey.PRIMARY_KEY_DELIMETER+userId);
    userCoursesMap.put(JsonKey.BATCH_ID , batchId);
    userCoursesMap.put(JsonKey.USER_ID, userId);
    userCoursesMap.put(JsonKey.REGISTERED_ORG_ID , orgId);
    userCoursesMap.put(JsonKey.PROGRESS , 1);
    ElasticSearchUtil.createData(EsIndex.sunbird.getIndexName(),EsType.usercourses.getTypeName() , batchId+JsonKey.PRIMARY_KEY_DELIMETER+userId , userCoursesMap);
  }

  private static void insertOrgDataToES(){
    Map<String , Object> orgMap = new HashMap<>();
    orgMap.put(JsonKey.ID , orgId);
    orgMap.put(JsonKey.ORGANISATION_NAME , "IIM");
    orgMap.put(JsonKey.ORGANISATION_ID , orgId);
    ElasticSearchUtil.createData(EsIndex.sunbird.getIndexName(),EsType.organisation.getTypeName() , batchId , orgMap);
  }

  @Test
  public void testCourseProgress(){

    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    actorMessage.put(JsonKey.REQUESTED_BY , userId);
    actorMessage.put(JsonKey.BATCH_ID , batchId);
    actorMessage.put(JsonKey.PERIOD , "fromBegining");
    actorMessage.setOperation(ActorOperations.COURSE_PROGRESS_METRICS.getValue());

    subject.tell(actorMessage, probe.getRef());
    Response res= probe.expectMsgClass(duration("100 second"),Response.class);
    System.out.println("SUCCESS");
    System.out.println("SUCCESS");

  }
  
  @SuppressWarnings({"unchecked", "deprecation"})
  @Test
  public void testCourseConsumption() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    actorMessage.put(JsonKey.COURSE_ID, "mclr309f39");
    actorMessage.put(JsonKey.PERIOD, "7d");
    actorMessage.put(JsonKey.REQUESTED_BY , userId);
    actorMessage.setOperation(ActorOperations.COURSE_CREATION_METRICS.getValue());

    subject.tell(actorMessage, probe.getRef());
    Response res = probe.expectMsgClass(duration("15 second"), Response.class);
    Map<String, Object> data = res.getResult();
    Assert.assertEquals("7d", data.get(JsonKey.PERIOD));
    Assert.assertEquals("mclr309f39", ((Map<String, Object>) data.get("course")).get(JsonKey.COURSE_ID));
    Map<String, Object> series = (Map<String, Object>) data.get(JsonKey.SERIES);
    Assert.assertTrue(series.containsKey("course.consumption.time_spent"));
    Assert.assertTrue(series.containsKey("course.consumption.content.users.count"));
    List<Map<String, Object>> buckets = (List<Map<String, Object>>) ((Map<String, Object>) series
        .get("course.consumption.content.users.count")).get("buckets");
    Assert.assertEquals(7, buckets.size());
    Map<String, Object> snapshot = (Map<String, Object>) data.get(JsonKey.SNAPSHOT);
    Assert.assertTrue(snapshot.containsKey("course.consumption.time_spent.count"));
    Assert.assertTrue(snapshot.containsKey("course.consumption.time_per_user"));
    Assert.assertTrue(snapshot.containsKey("course.consumption.users_completed"));
    Assert.assertTrue(snapshot.containsKey("course.consumption.time_spent_completion_count"));
  }

  
  @SuppressWarnings("deprecation")
  @Test
  public void testCourseConsumptionInvalidUserData() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    actorMessage.put(JsonKey.COURSE_ID, "mclr309f39_INVALID");
    actorMessage.put(JsonKey.PERIOD, "7d");
    actorMessage.put(JsonKey.REQUESTED_BY , userId + "Invalid");
    actorMessage.setOperation(ActorOperations.COURSE_CREATION_METRICS.getValue());

    subject.tell(actorMessage, probe.getRef());
    ProjectCommonException e = probe.expectMsgClass(duration("10 second"), ProjectCommonException.class);
    Assert.assertEquals("UNAUTHORIZE_USER", e.getCode());
  }
  
  @SuppressWarnings("deprecation")
  @Test
  public void testCourseConsumptionInvalidPeriod() {
    TestKit probe = new TestKit(system);
    ActorRef subject = system.actorOf(props);

    Request actorMessage = new Request();
    actorMessage.put(JsonKey.COURSE_ID, "mclr309f39");
    actorMessage.put(JsonKey.PERIOD, "10d");
    actorMessage.setOperation(ActorOperations.COURSE_CREATION_METRICS.getValue());

    subject.tell(actorMessage, probe.getRef());
    ProjectCommonException e = probe.expectMsgClass(duration("10 second"), ProjectCommonException.class);
    Assert.assertEquals("INVALID_PERIOD", e.getCode());
  }

}
