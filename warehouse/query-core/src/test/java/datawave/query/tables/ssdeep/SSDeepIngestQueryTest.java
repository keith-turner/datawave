package datawave.query.tables.ssdeep;

import static datawave.query.tables.ssdeep.util.SSDeepTestUtil.BUCKET_COUNT;
import static datawave.query.tables.ssdeep.util.SSDeepTestUtil.BUCKET_ENCODING_BASE;
import static datawave.query.tables.ssdeep.util.SSDeepTestUtil.BUCKET_ENCODING_LENGTH;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.collections4.iterators.TransformIterator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;

import com.google.common.collect.Sets;

import datawave.helpers.PrintUtility;
import datawave.ingest.mapreduce.handler.ssdeep.SSDeepIndexHandler;
import datawave.marking.MarkingFunctions;
import datawave.microservice.querymetric.QueryMetricFactoryImpl;
import datawave.query.RebuildingScannerTestHelper;
import datawave.query.tables.ssdeep.testframework.SSDeepDataType;
import datawave.query.tables.ssdeep.testframework.SSDeepFields;
import datawave.query.tables.ssdeep.testframework.SSDeepQueryTestTableHelper;
import datawave.query.tables.ssdeep.util.SSDeepTestUtil;
import datawave.query.testframework.AbstractFunctionalQuery;
import datawave.query.testframework.AccumuloSetup;
import datawave.query.testframework.DataTypeHadoopConfig;
import datawave.query.testframework.FieldConfig;
import datawave.query.testframework.FileType;
import datawave.query.testframework.QueryLogicTestHarness;
import datawave.security.authorization.DatawavePrincipal;
import datawave.security.authorization.DatawaveUser;
import datawave.security.authorization.SubjectIssuerDNPair;
import datawave.webservice.common.connection.AccumuloConnectionFactory;
import datawave.webservice.query.QueryImpl;
import datawave.webservice.query.logic.AbstractQueryLogicTransformer;
import datawave.webservice.query.logic.QueryLogic;
import datawave.webservice.query.result.event.DefaultResponseObjectFactory;
import datawave.webservice.query.result.event.EventBase;
import datawave.webservice.query.result.event.FieldBase;
import datawave.webservice.query.result.event.ResponseObjectFactory;
import datawave.webservice.query.runner.RunningQuery;
import datawave.webservice.result.EventQueryResponseBase;

public class SSDeepIngestQueryTest extends AbstractFunctionalQuery {

    @ClassRule
    public static AccumuloSetup accumuloSetup = new AccumuloSetup();

    private static final Logger log = Logger.getLogger(SSDeepIngestQueryTest.class);

    SSDeepSimilarityQueryLogic similarityQueryLogic;

    @BeforeClass
    public static void filterSetup() throws Exception {
        log.setLevel(Level.DEBUG);
        Logger printLog = Logger.getLogger(PrintUtility.class);
        printLog.setLevel(Level.DEBUG);

        Collection<DataTypeHadoopConfig> dataTypes = new ArrayList<>();
        FieldConfig generic = new SSDeepFields();
        dataTypes.add(new SSDeepDataType(SSDeepDataType.SSDeepEntry.ssdeep, generic));

        SSDeepQueryTestTableHelper ssDeepQueryTestTableHelper = new SSDeepQueryTestTableHelper(SSDeepIngestQueryTest.class.getName(), log,
                        RebuildingScannerTestHelper.TEARDOWN.EVERY_OTHER, RebuildingScannerTestHelper.INTERRUPT.NEVER);
        accumuloSetup.setData(FileType.CSV, dataTypes);
        client = accumuloSetup.loadTables(ssDeepQueryTestTableHelper);
    }

    @Before
    public void setupQuery() {
        MarkingFunctions markingFunctions = new MarkingFunctions.Default();
        ResponseObjectFactory responseFactory = new DefaultResponseObjectFactory();

        similarityQueryLogic = new SSDeepSimilarityQueryLogic();
        similarityQueryLogic.setTableName(SSDeepIndexHandler.DEFAULT_SSDEEP_INDEX_TABLE_NAME);
        similarityQueryLogic.setMarkingFunctions(markingFunctions);
        similarityQueryLogic.setResponseObjectFactory(responseFactory);
        similarityQueryLogic.setBucketEncodingBase(BUCKET_ENCODING_BASE);
        similarityQueryLogic.setBucketEncodingLength(BUCKET_ENCODING_LENGTH);
        similarityQueryLogic.setIndexBuckets(BUCKET_COUNT);

        // init must set auths
        testInit();

        SubjectIssuerDNPair dn = SubjectIssuerDNPair.of("userDn", "issuerDn");
        DatawaveUser user = new DatawaveUser(dn, DatawaveUser.UserType.USER, Sets.newHashSet(this.auths.toString().split(",")), null, null, -1L);
        principal = new DatawavePrincipal(Collections.singleton(user));

        testHarness = new QueryLogicTestHarness(this);
    }

    protected void testInit() {
        this.auths = SSDeepDataType.getTestAuths();
        this.documentKey = SSDeepDataType.SSDeepField.EVENT_ID.name();
    }

    public SSDeepIngestQueryTest() {
        super(SSDeepDataType.getManager());
    }

    @SuppressWarnings("rawtypes")
    @Test
    @Ignore
    public void testSSDeepSimilarity() throws Exception {
        log.info("------ testSSDeepSimilarity ------");
        @SuppressWarnings("SpellCheckingInspection")
        String testSSDeep = "384:nv/fP9FmWVMdRFj2aTgSO+u5QT4ZE1PIVS:nDmWOdRFNTTs504cQS";
        String query = "CHECKSUM_SSDEEP:" + testSSDeep;
        EventQueryResponseBase response = runSSDeepQuery(query, similarityQueryLogic, 0);

        List<EventBase> events = response.getEvents();
        Assert.assertEquals(1, events.size());
        Map<String,Map<String,String>> observedEvents = extractObservedEvents(events);

        SSDeepTestUtil.assertSSDeepSimilarityMatch(testSSDeep, testSSDeep, "38.0", "100", observedEvents);
    }

    @SuppressWarnings("rawtypes")
    public EventQueryResponseBase runSSDeepQuery(String query, QueryLogic<?> queryLogic, int minScoreThreshold) throws Exception {
        QueryImpl q = new QueryImpl();
        q.setQuery(query);
        q.setId(UUID.randomUUID());
        q.setPagesize(Integer.MAX_VALUE);
        q.setQueryAuthorizations(auths.toString());

        if (minScoreThreshold > 0) {
            q.addParameter(SSDeepSimilarityQueryTransformer.MIN_SSDEEP_SCORE_PARAMETER, String.valueOf(minScoreThreshold));
        }

        RunningQuery runner = new RunningQuery(client, AccumuloConnectionFactory.Priority.NORMAL, queryLogic, q, "", principal, new QueryMetricFactoryImpl());
        TransformIterator transformIterator = runner.getTransformIterator();
        AbstractQueryLogicTransformer<?,?> transformer = (AbstractQueryLogicTransformer<?,?>) transformIterator.getTransformer();
        return (EventQueryResponseBase) transformer.createResponse(runner.next());
    }

    /** Extract the events from a set of results into an easy to manage data structure for validation */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map<String,Map<String,String>> extractObservedEvents(List<EventBase> events) {
        int eventCount = events.size();
        Map<String,Map<String,String>> observedEvents = new HashMap<>();
        if (eventCount > 0) {
            for (EventBase e : events) {
                Map<String,String> observedFields = new HashMap<>();
                String querySsdeep = "UNKNOWN_QUERY";
                String matchingSsdeep = "UNKNOWN_MATCH";

                List<FieldBase> fields = e.getFields();
                for (FieldBase f : fields) {
                    if (f.getName().equals("QUERY_SSDEEP")) {
                        querySsdeep = f.getValueString();
                    }
                    if (f.getName().equals("MATCHING_SSDEEP")) {
                        matchingSsdeep = f.getValueString();
                    }
                    observedFields.put(f.getName(), f.getValueString());
                }

                String eventKey = querySsdeep + "#" + matchingSsdeep;
                observedEvents.put(eventKey, observedFields);
            }
        }
        return observedEvents;
    }
}