package com.indeed.proctor.webapp.controllers;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.indeed.proctor.common.EnvironmentVersion;
import com.indeed.proctor.common.IncompatibleTestMatrixException;
import com.indeed.proctor.common.ProctorLoadResult;
import com.indeed.proctor.common.ProctorPromoter;
import com.indeed.proctor.common.ProctorSpecification;
import com.indeed.proctor.common.ProctorUtils;
import com.indeed.proctor.common.TestSpecification;
import com.indeed.proctor.common.model.Allocation;
import com.indeed.proctor.common.model.ConsumableTestDefinition;
import com.indeed.proctor.common.model.Payload;
import com.indeed.proctor.common.model.Range;
import com.indeed.proctor.common.model.TestBucket;
import com.indeed.proctor.common.model.TestDefinition;
import com.indeed.proctor.common.model.TestMatrixArtifact;
import com.indeed.proctor.common.model.TestMatrixDefinition;
import com.indeed.proctor.common.model.TestMatrixVersion;
import com.indeed.proctor.common.model.TestType;
import com.indeed.proctor.store.ProctorStore;
import com.indeed.proctor.store.Revision;
import com.indeed.proctor.store.StoreException;
import com.indeed.proctor.store.GitNoAuthorizationException;
import com.indeed.proctor.store.GitNoMasterAccessLevelException;
import com.indeed.proctor.store.GitNoDevelperAccessLevelException;
import com.indeed.proctor.webapp.ProctorSpecificationSource;
import com.indeed.proctor.webapp.controllers.BackgroundJob.ResultUrl;
import com.indeed.proctor.webapp.db.Environment;
import com.indeed.proctor.webapp.extensions.DefinitionChangeLog;
import com.indeed.proctor.webapp.extensions.PostDefinitionCreateChange;
import com.indeed.proctor.webapp.extensions.PostDefinitionDeleteChange;
import com.indeed.proctor.webapp.extensions.PostDefinitionEditChange;
import com.indeed.proctor.webapp.extensions.PostDefinitionPromoteChange;
import com.indeed.proctor.webapp.extensions.PreDefinitionCreateChange;
import com.indeed.proctor.webapp.extensions.PreDefinitionDeleteChange;
import com.indeed.proctor.webapp.extensions.PreDefinitionEditChange;
import com.indeed.proctor.webapp.extensions.PreDefinitionPromoteChange;
import com.indeed.proctor.webapp.extensions.RevisionCommitCommentFormatter;
import com.indeed.proctor.webapp.model.AppVersion;
import com.indeed.proctor.webapp.model.ProctorClientApplication;
import com.indeed.proctor.webapp.model.RevisionDefinition;
import com.indeed.proctor.webapp.model.SessionViewModel;
import com.indeed.proctor.webapp.model.WebappConfiguration;
import com.indeed.proctor.webapp.tags.TestDefinitionFunctions;
import com.indeed.proctor.webapp.tags.UtilityFunctions;
import com.indeed.proctor.webapp.util.threads.LogOnUncaughtExceptionHandler;
import com.indeed.proctor.webapp.views.JsonView;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author parker
 */
@Controller
@RequestMapping({"/definition", "/proctor/definition"})
public class ProctorTestDefinitionController extends AbstractController {
    private static final Logger LOGGER = Logger.getLogger(ProctorTestDefinitionController.class);

    private static final Pattern ALPHA_NUMERIC_JAVA_IDENTIFIER_PATTERN = Pattern.compile("^[a-z_][a-z0-9_]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALID_TEST_NAME_PATTERN = ALPHA_NUMERIC_JAVA_IDENTIFIER_PATTERN;
    private static final Pattern VALID_BUCKET_NAME_PATTERN = ALPHA_NUMERIC_JAVA_IDENTIFIER_PATTERN;

    private final ProctorPromoter promoter;

    private final ProctorSpecificationSource specificationSource;
    private final int verificationTimeout;
    private final ExecutorService verifierExecutor;

    private final BackgroundJobManager jobManager;
    private final BackgroundJobFactory jobFactory;

    /*
       TODO: preDefinitionChanges and postDefinitionChanges should be included in the autowird constructor.
       Four constructors would need to be made, which leads to type erasure problems.
     */
    @Autowired(required=false)
    private List<PreDefinitionEditChange> preDefinitionEditChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PostDefinitionEditChange> postDefinitionEditChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PreDefinitionCreateChange> preDefinitionCreateChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PostDefinitionCreateChange> postDefinitionCreateChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PreDefinitionDeleteChange> preDefinitionDeleteChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PostDefinitionDeleteChange> postDefinitionDeleteChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PreDefinitionPromoteChange> preDefinitionPromoteChanges = Collections.emptyList();
    @Autowired(required=false)
    private List<PostDefinitionPromoteChange> postDefinitionPromoteChanges = Collections.emptyList();
    @Autowired(required=false)
    private RevisionCommitCommentFormatter revisionCommitCommentFormatter;



    private static enum Views {
        DETAILS("definition/details"),
        EDIT("definition/edit"),
        CREATE("definition/edit");

        private final String name;

        private Views(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }




    @Autowired
    public ProctorTestDefinitionController(final WebappConfiguration configuration,
                                           @Qualifier("trunk") final ProctorStore trunkStore,
                                           @Qualifier("qa") final ProctorStore qaStore,
                                           @Qualifier("production") final ProctorStore productionStore,
                                           final ProctorPromoter promoter,
                                           final ProctorSpecificationSource specificationSource,
                                           final BackgroundJobManager jobManager,
                                           final BackgroundJobFactory jobFactory) {
        super(configuration, trunkStore, qaStore, productionStore);
        this.promoter = promoter;
        this.jobManager = jobManager;
        this.jobFactory = jobFactory;

        this.verificationTimeout = configuration.getVerifyHttpTimeout();
        this.specificationSource = specificationSource;
        Preconditions.checkArgument(verificationTimeout > 0, "verificationTimeout > 0");
        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("proctor-verifiers-Thread-%d")
                .setUncaughtExceptionHandler(new LogOnUncaughtExceptionHandler())
                .build();
        this.verifierExecutor = Executors.newFixedThreadPool(configuration.getVerifyExecutorThreads(), threadFactory);

    }

    @RequestMapping(value = "/create", method = RequestMethod.GET)
    public String create(
        final Model model
    ) {

        final TestDefinition definition = new TestDefinition(
            "" /* version */,
            null /* rule */,
            TestType.USER /* testType */,
            "" /* salt */,
            Collections.<TestBucket>emptyList(),
            Lists.<Allocation>newArrayList(
                new Allocation(null, Collections.<Range>emptyList())
            ),
            Collections.<String, Object>emptyMap(),
            Collections.<String, Object>emptyMap(),
            "" /* description */
        );
        final List<RevisionDefinition> history = Collections.emptyList();
        final EnvironmentVersion version = null;
        return doView(Environment.WORKING, Views.CREATE, "", definition, history, version, model);
    }

    @RequestMapping(value = "/{testName}", method = RequestMethod.GET)
    public String show(
        HttpServletResponse response,
        @PathVariable final String testName,
        @RequestParam(required = false) final String branch,
        @RequestParam(required = false, defaultValue = "", value = "r") final String revision,
        @RequestParam(required = false, value = "alloc_hist") final String loadAllocHistParam,
        @CookieValue(value="loadAllocationHistory", defaultValue = "") String loadAllocHistCookie,
        final Model model
    ) throws StoreException {
        final Environment theEnvironment = determineEnvironmentFromParameter(branch);
        final ProctorStore store = determineStoreFromEnvironment(theEnvironment);

        // Git performance suffers when there are many concurrent operations
        // Only request full test history for one test at a time
        final EnvironmentVersion version;
        final List<RevisionDefinition> history;
        final TestDefinition definition;
        synchronized (this) {
            version = promoter.getEnvironmentVersion(testName);

            if (revision.length() > 0) {
                definition = getTestDefinition(store, testName, revision);
            } else {
                definition = getTestDefinition(store, testName);
            }

            if (definition == null) {
                LOGGER.info("Unknown test definition : " + testName + " revision " + revision);
                // unknown testdefinition
                if (testNotExistsInAnyEnvs(theEnvironment, testName, revision)){
                    return "404";
                }
                final String errorMsg = "Test \"" + testName + "\" " +
                        (revision.isEmpty() ? "" : "of revision " + revision + " ") +
                        "does not exist in " + branch + " branch! Please check other branches.";
                return doView(theEnvironment, Views.DETAILS, errorMsg, new TestDefinition(), new ArrayList<>(), version, model);
            }
            final boolean loadAllocHistory = shouldLoadAllocationHistory(loadAllocHistParam, loadAllocHistCookie, response);
            history = makeRevisionDefinitionList(store, testName, version.getRevision(theEnvironment), loadAllocHistory);
        }

        return doView(theEnvironment, Views.DETAILS, testName, definition, history, version, model);
    }

    private boolean testNotExistsInAnyEnvs(final Environment theEnvironment, final String testName, final String revision) {
        return Stream.of(Environment.values())
                .filter(env -> !theEnvironment.equals(env))
                .allMatch(env -> getTestDefinition(env, testName, revision) == null);
    }

    private List<RevisionDefinition> makeRevisionDefinitionList(final ProctorStore store,
                                                                final String testName,
                                                                final String startRevision,
                                                                final boolean useDefinitions) {
        final List<Revision> history = getTestHistory(store, testName, startRevision);
        final List<RevisionDefinition> revisionDefinitions = new ArrayList<RevisionDefinition>();
        if(useDefinitions) {
            for (Revision revision : history) {
                final String revisionName = revision.getRevision();
                final TestDefinition definition = getTestDefinition(store, testName, revisionName);
                revisionDefinitions.add(new RevisionDefinition(revision, definition));
            }
        }
        else {
            for (Revision revision : history) {
                revisionDefinitions.add(new RevisionDefinition(revision, null));
            }
        }
        return revisionDefinitions;
    }

    private boolean shouldLoadAllocationHistory(String loadAllocHistParam, String loadAllocHistCookie, HttpServletResponse response) {
        if (loadAllocHistParam != null) {
            if (loadAllocHistParam.equals("true") || loadAllocHistParam.equals("1")) {
                final Cookie lahCookie = new Cookie("loadAllocationHistory", "true");
                final int thirtyMinutes = 60 * 30;
                lahCookie.setMaxAge(thirtyMinutes);
                lahCookie.setPath("/");
                response.addCookie(lahCookie);
                return true;
            } else {
                final Cookie deletionCookie = new Cookie("loadAllocationHistory", "");
                deletionCookie.setMaxAge(0);
                deletionCookie.setPath("/");
                response.addCookie(deletionCookie);
                return false;
            }
        } else if (loadAllocHistCookie.equals("true") || loadAllocHistCookie.equals("false")) {
            return Boolean.parseBoolean(loadAllocHistCookie);
        } else {
            return false;
        }
    }

    @RequestMapping(value = "/{testName}/edit", method = RequestMethod.GET)
    public String doEditGet(
        @PathVariable String testName,
        final Model model
    ) throws StoreException {
        final Environment theEnvironment = Environment.WORKING; // only allow editing of TRUNK!
        final ProctorStore store = determineStoreFromEnvironment(theEnvironment);
        final EnvironmentVersion version = promoter.getEnvironmentVersion(testName);

        final TestDefinition definition = getTestDefinition(store, testName);
        if (definition == null) {
            LOGGER.info("Unknown test definition : " + testName);
            // unknown testdefinition
            return "404";
        }

        return doView(theEnvironment, Views.EDIT, testName, definition, Collections.<RevisionDefinition>emptyList(), version, model);
    }

    @RequestMapping(value = "/{testName}/delete", method = RequestMethod.POST)
    public View doDeletePost(
        @PathVariable final String testName,
        @RequestParam(required = false) String src,
        @RequestParam(required = false) final String srcRevision,

        @RequestParam(required = false) final String username,
        @RequestParam(required = false) final String password,
        @RequestParam(required = false, defaultValue = "") final String comment,
        final HttpServletRequest request,
        final Model model
    ) {
        final Environment theEnvironment = determineEnvironmentFromParameter(src);

        Map<String, String[]> requestParameterMap = new HashMap<String, String[]>();
        requestParameterMap.putAll(request.getParameterMap());
        final String nonEmptyComment = formatDefaultDeleteComment(testName, comment);
        final BackgroundJob<Boolean> job = createDeleteBackgroundJob(testName,
            theEnvironment,
            srcRevision,
            username,
            password,
            nonEmptyComment,
            requestParameterMap);
        jobManager.submit(job);

        if (isAJAXRequest(request)) {
            final JsonResponse<Map> response = new JsonResponse<Map>(BackgroundJobRpcController.buildJobJson(job), true, job.getTitle());
            return new JsonView(response);
        } else {
            // redirect to a status page for the job id
            return new RedirectView("/proctor/rpc/jobs/list?id=" + job.getId());
        }

    }

    private BackgroundJob<Boolean> createDeleteBackgroundJob(
        final String testName,
        final Environment source,
        final String srcRevision,

        final String username,
        final String password,
        final String comment,
        final Map<String, String[]> requestParameterMap


    ) {
        LOGGER.info(String.format("Deleting test %s branch: %s user: %s ", testName, source, username));
        return jobFactory.createBackgroundJob(
                String.format("(%s) deleting %s branch: %s ", username, testName, source),
                BackgroundJob.JobType.TEST_DELETION,
                new BackgroundJobFactory.Executor<Boolean>() {
                    @Override
                    public Boolean execute(final BackgroundJob job) {
                        final ProctorStore store = determineStoreFromEnvironment(source);
                        final TestDefinition definition = getTestDefinition(store, testName);
                        if (definition == null) {
                            job.log("Unknown test definition : " + testName);
                            return false;
                        }

                        try {
                            validateUsernamePassword(username, password);

                            final Revision prevVersion;
                            job.log("(scm) getting history for '" + testName + "'");
                            final List<Revision> history = getTestHistory(store, testName, 1);
                            if (history.size() > 0) {
                                prevVersion = history.get(0);
                                if (!prevVersion.getRevision().equals(srcRevision)) {
                                    throw new IllegalArgumentException("Test has been updated since " + srcRevision + " currently at " + prevVersion.getRevision());
                                }
                            } else {
                                throw new IllegalArgumentException("Could not get any history for " + testName);
                            }

                            final String fullComment = formatFullComment(comment, requestParameterMap);

                            if (source.equals(Environment.WORKING) || source.equals(Environment.QA)) {
                                final CheckMatrixResult checkMatrixResultInQa = checkMatrix(Environment.QA, testName, null);
                                if (!checkMatrixResultInQa.isValid) {
                                    throw new IllegalArgumentException("There are still clients in QA using " + testName + " " + checkMatrixResultInQa.getErrors().get(0));
                                }
                                final CheckMatrixResult checkMatrixResultInProd = checkMatrix(Environment.PRODUCTION, testName, null);
                                if (!checkMatrixResultInProd.isValid) {
                                    throw new IllegalArgumentException("There are still clients in prod using " + testName + " " + checkMatrixResultInProd.getErrors().get(0));
                                }
                            } else {
                                final CheckMatrixResult checkMatrixResult = checkMatrix(source, testName, null);
                                if (!checkMatrixResult.isValid()) {
                                    throw new IllegalArgumentException("There are still clients in prod using " + testName + " " + checkMatrixResult.getErrors().get(0));
                                }
                            }

                            //PreDefinitionDeleteChanges
                            job.log("Executing pre delete extension tasks.");
                            for (final PreDefinitionDeleteChange preDefinitionDeleteChange: preDefinitionDeleteChanges) {
                                final DefinitionChangeLog definitionChangeLog = preDefinitionDeleteChange.preDelete(definition, requestParameterMap);
                                logDefinitionChangeLog(definitionChangeLog, preDefinitionDeleteChange.getClass().getSimpleName(), job);
                            }

                            job.log("(svn) delete " + testName);
                            store.deleteTestDefinition(username, password, srcRevision, testName, definition, fullComment);

                            boolean testExistsInOtherEnvironments = false;
                            for (final Environment otherEnvironment : Environment.values()) {
                                if (otherEnvironment != source) {
                                    final ProctorStore otherStore = determineStoreFromEnvironment(otherEnvironment);
                                    final TestDefinition otherDefinition = getTestDefinition(otherStore, testName);
                                    if (otherDefinition != null) {
                                        testExistsInOtherEnvironments = true;
                                        job.addUrl("/proctor/definition/" + UtilityFunctions.urlEncode(testName) + "?branch=" + otherEnvironment.getName(), "view " + testName + " on " + otherEnvironment.getName());
                                    }
                                }
                            }
                            if (!testExistsInOtherEnvironments) {
                                job.setEndMessage("This test no longer exists in any environment.");
                            }

                            //PostDefinitionDeleteChanges
                            job.log("Executing post delete extension tasks.");
                            for (final PostDefinitionDeleteChange postDefinitionDeleteChange: postDefinitionDeleteChanges) {
                                final DefinitionChangeLog definitionChangeLog = postDefinitionDeleteChange.postDelete(requestParameterMap);
                                logDefinitionChangeLog(definitionChangeLog, postDefinitionDeleteChange.getClass().getSimpleName(), job);
                            }


                        } catch (final GitNoMasterAccessLevelException | GitNoAuthorizationException | GitNoDevelperAccessLevelException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Deletion Failed: " + job.getTitle(), exp);
                        } catch (StoreException.TestUpdateException exp) {
                            job.logFailedJob(exp);
                            LOGGER.error("Deletion Failed: " + job.getTitle(), exp);
                        } catch (IllegalArgumentException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Deletion Failed: " + job.getTitle(), exp);
                        } catch (Exception e) {
                            job.logFailedJob(e);
                            LOGGER.error("Deletion Failed: " + job.getTitle(), e);
                        }
                        return null;
                    }
                }
        );
    }


    @RequestMapping(value = "/{testName}/promote", method = RequestMethod.POST)
    public View doPromotePost(
        @PathVariable final String testName,
        @RequestParam(required = false) final String username,
        @RequestParam(required = false) final String password,

        @RequestParam(required = false) final String src,
        @RequestParam(required = false) final String srcRevision,
        @RequestParam(required = false) final String dest,
        @RequestParam(required = false) final String destRevision,
        final HttpServletRequest request,
        final Model model
    ) {
        final Environment source = determineEnvironmentFromParameter(src);
        final Environment destination = determineEnvironmentFromParameter(dest);

        final Map<String, String[]> requestParameterMap = new HashMap<String, String[]>();
        requestParameterMap.putAll(request.getParameterMap());
        final BackgroundJob job = doPromoteInternal(testName, username, password, source, srcRevision, destination, destRevision, requestParameterMap);
        jobManager.submit(job);

        if (isAJAXRequest(request)) {
            final JsonResponse<Map> response = new JsonResponse<Map>(BackgroundJobRpcController.buildJobJson(job), true, job.getTitle());
            return new JsonView(response);
        } else {
            return new RedirectView("/proctor/definition/" + UtilityFunctions.urlEncode(testName) + "?branch=" + destination.getName());
        }
    }

    private BackgroundJob doPromoteInternal(final String testName,
                                            final String username,
                                            final String password,
                                            final Environment source,
                                            final String srcRevision,
                                            final Environment destination,
                                            final String destRevision,
                                            final Map<String, String[]> requestParameterMap
    ) {
        return jobFactory.createBackgroundJob(
                String.format("(%s) promoting %s %s %1.7s to %s", username, testName, source, srcRevision, destination),
                BackgroundJob.JobType.TEST_PROMOTION,
                new BackgroundJobFactory.Executor() {
                    @Override
                    public Object execute(final BackgroundJob job) {
                        /*
                            Valid permutations:
                            TRUNK -> QA
                            TRUNK -> PRODUCTION
                            QA -> PRODUCTION
                         */
                        try {
                            doJobIndependentPromoteInternal(testName, username, password, source, srcRevision, destination, destRevision, requestParameterMap, job, false);
                        } catch (final GitNoAuthorizationException | GitNoMasterAccessLevelException | GitNoDevelperAccessLevelException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Promotion Failed: " + job.getTitle(), exp);
                        } catch (ProctorPromoter.TestPromotionException exp) {
                            job.logFailedJob(exp);
                            LOGGER.error("Promotion Failed: " + job.getTitle(), exp);
                        } catch (StoreException.TestUpdateException exp) {
                            job.logFailedJob(exp);
                            LOGGER.error("Promotion Failed: " + job.getTitle(), exp);
                        } catch (IllegalArgumentException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Promotion Failed: " + job.getTitle(), exp);
                        } catch (Exception exp) {
                            job.logFailedJob(exp);
                            LOGGER.error("Promotion Failed: " + job.getTitle(), exp);
                        }

                        return null;
                    }
                }
        );
    }
    private boolean doJobIndependentPromoteInternal(final String testName,
                                                    final String username,
                                                    final String password,
                                                    final Environment source,
                                                    final String srcRevision,
                                                    final Environment destination,
                                                    final String destRevision,
                                                    final Map<String, String[]> requestParameterMap,
                                                    final BackgroundJob job,
                                                    final boolean isAutopromote) throws Exception {
        final Map<String, String> metadata = Collections.emptyMap();
        validateUsernamePassword(username, password);

        // TODO (parker) 9/5/12 - Verify that promoting to the destination branch won't cause issues
        final TestDefinition testDefintion = getTestDefinition(source, testName, srcRevision);
        //            if(d == null) {
        //                return "could not find " + testName + " on " + source + " with revision " + srcRevision;
        //            }

        final CheckMatrixResult result = checkMatrix(destination, testName, testDefintion);
        if (!result.isValid()) {
            throw new IllegalArgumentException(String.format("Test Promotion not compatible, errors: %s", Joiner.on("\n").join(result.getErrors())));
        } else {
            final Map<Environment, PromoteAction> actions = PROMOTE_ACTIONS.get(source);
            if (actions == null || !actions.containsKey(destination)) {
                throw new IllegalArgumentException("Invalid combination of source and destination: source=" + source + " dest=" + destination);
            }
            final PromoteAction action = actions.get(destination);

            //PreDefinitionPromoteChanges
            job.log("Executing pre promote extension tasks.");
            for (final PreDefinitionPromoteChange preDefinitionPromoteChange: preDefinitionPromoteChanges) {
                final DefinitionChangeLog definitionChangeLog = preDefinitionPromoteChange.prePromote(testDefintion, requestParameterMap, source, destination, isAutopromote);
                logDefinitionChangeLog(definitionChangeLog, preDefinitionPromoteChange.getClass().getSimpleName(), job);
            }

            //Promote Change
            final boolean success = action.promoteTest(job, testName, srcRevision, destRevision, username, password, metadata);

            //PostDefinitionPromoteChanges
            job.log("Executing post promote extension tasks.");
            for (final PostDefinitionPromoteChange postDefinitionPromoteChange: postDefinitionPromoteChanges) {
                final DefinitionChangeLog definitionChangeLog = postDefinitionPromoteChange.postPromote(requestParameterMap, source, destination, isAutopromote);
                logDefinitionChangeLog(definitionChangeLog, postDefinitionPromoteChange.getClass().getSimpleName(), job);
            }


            job.log(String.format("Promoted %s from %s (%1.7s) to %s (%1.7s)", testName, source.getName(), srcRevision, destination.getName(), destRevision));
            job.addUrl("/proctor/definition/" + UtilityFunctions.urlEncode(testName) + "?branch=" + destination.getName(), "view " + testName + " on " + destination.getName());
            return success;
        }
    }

    private void logDefinitionChangeLog(DefinitionChangeLog definitionChangeLog, String changeName, BackgroundJob backgroundJob) {
        if (definitionChangeLog != null) {
            final List<ResultUrl> urls = definitionChangeLog.getUrls();
            if (urls != null) {
                for (final ResultUrl url : urls) {
                    backgroundJob.addUrl(url);
                }
            }

            final List<String> changeLog = definitionChangeLog.getLog();
            if (changeLog != null) {
                for (final String logMessage : changeLog) {
                    backgroundJob.log(logMessage);
                }
            }

            if (definitionChangeLog.isErrorsFound()) {
                throw new IllegalArgumentException(changeName + " failed with the following errors: " + definitionChangeLog.getErrors());
            }
        }
    }

    private static interface PromoteAction {
        Environment getSource();

        Environment getDestination();

        boolean promoteTest(BackgroundJob job,
                            final String testName,
                            final String srcRevision,
                            final String destRevision,
                            final String username,
                            final String password,
                            final Map<String, String> metadata) throws IllegalArgumentException, ProctorPromoter.TestPromotionException, StoreException.TestUpdateException;
    }

    private abstract class PromoteActionBase implements PromoteAction {
        final Environment src;
        final Environment destination;


        protected PromoteActionBase(final Environment src,
                                    final Environment destination) {
            this.destination = destination;
            this.src = src;
        }

        @Override
        public boolean promoteTest(final BackgroundJob job,
                                   final String testName,
                                   final String srcRevision,
                                   final String destRevision,
                                   final String username,
                                   final String password,
                                   final Map<String, String> metadata) throws IllegalArgumentException, ProctorPromoter.TestPromotionException, StoreException.TestUpdateException, StoreException.TestUpdateException {
            try {
                doPromotion(job, testName, srcRevision, destRevision, username, password, metadata);
                return true;
            } catch (Exception t) {
                Throwables.propagateIfInstanceOf(t, ProctorPromoter.TestPromotionException.class);
                Throwables.propagateIfInstanceOf(t, StoreException.TestUpdateException.class);
                throw Throwables.propagate(t);
            }
        }

        @Override
        public final Environment getSource() {
            return src;
        }

        @Override
        public final Environment getDestination() {
            return destination;
        }

        abstract void doPromotion(BackgroundJob job, String testName, String srcRevision, String destRevision,
                                  String username, String password, Map<String, String> metadata)
                throws ProctorPromoter.TestPromotionException, StoreException;
    }

    private final PromoteAction TRUNK_TO_QA = new PromoteActionBase(Environment.WORKING,
                                                                    Environment.QA) {
        @Override
        void doPromotion(final BackgroundJob job,
                         final String testName,
                         final String srcRevision,
                         final String destRevision,
                         final String username,
                         final String password,
                         final Map<String, String> metadata)
                throws ProctorPromoter.TestPromotionException, StoreException {
            job.log(String.format("(scm) promote %s %1.7s (trunk to qa)", testName, srcRevision));
            promoter.promoteTrunkToQa(testName, srcRevision, destRevision, username, password, metadata);
        }
    };

    private final PromoteAction TRUNK_TO_PRODUCTION = new PromoteActionBase(Environment.WORKING,
                                                                            Environment.PRODUCTION) {
        @Override
        void doPromotion(final BackgroundJob job,
                         final String testName,
                         final String srcRevision,
                         final String destRevision,
                         final String username,
                         final String password,
                         final Map<String, String> metadata)
                throws ProctorPromoter.TestPromotionException, StoreException {
            job.log(String.format("(scm) promote %s %1.7s (trunk to production)", testName, srcRevision));
            promoter.promoteTrunkToProduction(testName, srcRevision, destRevision, username, password, metadata);
        }
    };

    private final PromoteAction QA_TO_PRODUCTION = new PromoteActionBase(Environment.QA,
                                                                         Environment.PRODUCTION) {
        @Override
        void doPromotion(final BackgroundJob job,
                         final String testName,
                         final String srcRevision,
                         final String destRevision,
                         final String username,
                         final String password,
                         final Map<String, String> metadata) throws ProctorPromoter.TestPromotionException, StoreException {
            job.log(String.format("(scm) promote %s %1.7s (qa to production)", testName, srcRevision));
            promoter.promoteQaToProduction(testName, srcRevision, destRevision, username, password, metadata);
        }
    };


    private final Map<Environment, Map<Environment, PromoteAction>> PROMOTE_ACTIONS = ImmutableMap.<Environment, Map<Environment, PromoteAction>>builder()
        .put(Environment.WORKING, ImmutableMap.of(Environment.QA, TRUNK_TO_QA, Environment.PRODUCTION, TRUNK_TO_PRODUCTION))
        .put(Environment.QA, ImmutableMap.of(Environment.PRODUCTION, QA_TO_PRODUCTION)).build();


    @RequestMapping(value = "/{testName}/edit", method = RequestMethod.POST)
    public View doEditPost(
        @PathVariable final String testName,
        @RequestParam(required = false) final String username,
        @RequestParam(required = false) final String password,
        @RequestParam(required = false, defaultValue = "false") final boolean isCreate,
        @RequestParam(required = false, defaultValue = "") final String comment,
        @RequestParam(required = false) final String testDefinition, // testDefinition is JSON representation of test-definition
        @RequestParam(required = false, defaultValue = "") final String previousRevision,
        @RequestParam(required = false, defaultValue = "false") final boolean isAutopromote,
        final HttpServletRequest request,
        final Model model) {

        //TODO: Remove all internal params and just pass request.getParameterMap() to doEditPost() as map of fields and values

        Map<String, String[]> requestParameterMap = new HashMap<String, String[]>();
        requestParameterMap.putAll(request.getParameterMap());
        final String nonEmptyComment;
        if (isCreate) {
            nonEmptyComment = formatDefaultCreateComment(testName, comment);
        } else {
            nonEmptyComment = formatDefaultUpdateComment(testName, comment);
        }
        final BackgroundJob job = doEditPost(testName,
                                             username,
                                             password,
                                             isCreate,
                                             nonEmptyComment,
                                             testDefinition,
                                             previousRevision,
                                             isAutopromote,
                                             requestParameterMap);
        jobManager.submit(job);
        if (isAJAXRequest(request)) {
            final JsonResponse<Map> response = new JsonResponse<Map>(BackgroundJobRpcController.buildJobJson(job), true, job.getTitle());
            return new JsonView(response);
        } else {
            // redirect to a status page for the job id
            return new RedirectView("/proctor/rpc/jobs/list?id=" + job.getId());
        }
    }

    private BackgroundJob<Boolean> doEditPost(
        final String testName,
        final String username,
        final String password,
        final boolean isCreate,
        final String comment,
        final String testDefinitionJson,
        final String previousRevision,
        final boolean isAutopromote,
        final Map<String, String[]> requestParameterMap) {

        return jobFactory.createBackgroundJob(
                String.format("(%s) %s %s", username, (isCreate ? "Creating" : "Editing"), testName),
                isCreate ? BackgroundJob.JobType.TEST_CREATION : BackgroundJob.JobType.TEST_EDIT,
                new BackgroundJobFactory.Executor<Boolean>() {
                    @Override
                    public Boolean execute(final BackgroundJob job) {
                        final Environment theEnvironment = Environment.WORKING; // only allow editing of TRUNK!
                        final ProctorStore store = determineStoreFromEnvironment(theEnvironment);
                        final EnvironmentVersion environmentVersion = promoter.getEnvironmentVersion(testName);
                        final String qaRevision = environmentVersion == null ? EnvironmentVersion.UNKNOWN_REVISION : environmentVersion.getQaRevision();
                        final String prodRevision = environmentVersion == null ? EnvironmentVersion.UNKNOWN_REVISION : environmentVersion.getProductionRevision();

                        try {
                            if (CharMatcher.WHITESPACE.matchesAllOf(Strings.nullToEmpty(testDefinitionJson))) {
                                throw new IllegalArgumentException("No new test definition given");
                            }
                            validateUsernamePassword(username, password);
                            validateComment(comment);

                            final Revision prevVersion;
                            if (previousRevision.length() > 0) {
                                job.log("(scm) getting history for '" + testName + "'");
                                final List<Revision> history = getTestHistory(store, testName, 1);
                                if (history.size() > 0) {
                                    prevVersion = history.get(0);
                                    if (!prevVersion.getRevision().equals(previousRevision)) {
                                        throw new IllegalArgumentException("Test has been updated since " + previousRevision + " currently at " + prevVersion.getRevision());
                                    }
                                } else {
                                    prevVersion = null;
                                }
                            } else {
                                // Create flow
                                prevVersion = null;
                                // check that the test name is valid
                                if (!isValidTestName(testName)) {
                                    throw new IllegalArgumentException("Test Name must be alpha-numeric underscore and not start with a number, found: '" + testName + "'");
                                }
                            }

                            final TestDefinition testDefinitionToUpdate;
                            job.log("Parsing test definition json");
                            testDefinitionToUpdate = TestDefinitionFunctions.parseTestDefinition(testDefinitionJson);

                            //  TODO: make these parameters
                            final boolean skipVerification = true;
                            //  TODO: make these parameters
                            final boolean allowInstanceFailure = true;

                            final ProctorStore trunkStore = determineStoreFromEnvironment(Environment.WORKING);
                            job.log("(scm) loading existing test definition for '" + testName + "'");
                            // Getting the TestDefinition via currentTestMatrix instead of trunkStore.getTestDefinition because the test
                            final TestDefinition existingTestDefinition = trunkStore.getCurrentTestMatrix().getTestMatrixDefinition().getTests().get(testName);
                            if (previousRevision.length() <= 0 && existingTestDefinition != null) {
                                throw new IllegalArgumentException("Current tests exists with name : '" + testName + "'");
                            }

                            if (testDefinitionToUpdate.getTestType() == null && existingTestDefinition != null) {
                                testDefinitionToUpdate.setTestType(existingTestDefinition.getTestType());
                            }
                            if (isCreate) {
                                testDefinitionToUpdate.setVersion("-1");
                            } else if (existingTestDefinition != null) {
                                testDefinitionToUpdate.setVersion(existingTestDefinition.getVersion());
                            }
                            job.log("verifying test definition and buckets");
                            validateBasicInformation(testDefinitionToUpdate, job);

                            final ConsumableTestDefinition consumableTestDefinition = ProctorUtils.convertToConsumableTestDefinition(testDefinitionToUpdate);
                            ProctorUtils.verifyInternallyConsistentDefinition(testName, "edit", consumableTestDefinition);

                            //PreDefinitionEdit
                            if (isCreate) {
                                job.log("Executing pre create extension tasks.");
                                for (final PreDefinitionCreateChange preDefinitionCreateChange : preDefinitionCreateChanges) {
                                    final DefinitionChangeLog definitionChangeLog = preDefinitionCreateChange.preCreate(testDefinitionToUpdate, requestParameterMap);
                                    logDefinitionChangeLog(definitionChangeLog, preDefinitionCreateChange.getClass().getSimpleName(), job);
                                }
                            } else {
                                job.log("Executing pre edit extension tasks.");
                                for (final PreDefinitionEditChange preDefinitionEditChange : preDefinitionEditChanges) {
                                    final DefinitionChangeLog definitionChangeLog = preDefinitionEditChange.preEdit(existingTestDefinition, testDefinitionToUpdate, requestParameterMap);
                                    logDefinitionChangeLog(definitionChangeLog, preDefinitionEditChange.getClass().getSimpleName(), job);
                                }
                            }

                            final String fullComment = formatFullComment(comment, requestParameterMap);

                            //Change definition
                            final Map<String, String> metadata = Collections.emptyMap();
                            if (existingTestDefinition == null) {
                                job.log("(scm) adding test definition");
                                trunkStore.addTestDefinition(username, password, testName, testDefinitionToUpdate, metadata, fullComment);
                                promoter.refreshWorkingVersion(testName);
                            } else {
                                job.log("(scm) updating test definition");
                                trunkStore.updateTestDefinition(username, password, previousRevision, testName, testDefinitionToUpdate, metadata, fullComment);
                                promoter.refreshWorkingVersion(testName);
                            }

                            //PostDefinitionEdit
                            if (isCreate) {
                                job.log("Executing post create extension tasks.");
                                for (final PostDefinitionCreateChange postDefinitionCreateChange : postDefinitionCreateChanges) {
                                    final DefinitionChangeLog definitionChangeLog = postDefinitionCreateChange.postCreate(testDefinitionToUpdate, requestParameterMap);
                                    logDefinitionChangeLog(definitionChangeLog, postDefinitionCreateChange.getClass().getSimpleName(), job);

                                }
                            } else {
                                job.log("Executing post edit extension tasks.");
                                for (final PostDefinitionEditChange postDefinitionEditChange : postDefinitionEditChanges) {
                                    final DefinitionChangeLog definitionChangeLog = postDefinitionEditChange.postEdit(existingTestDefinition, testDefinitionToUpdate, requestParameterMap);
                                    logDefinitionChangeLog(definitionChangeLog, postDefinitionEditChange.getClass().getSimpleName(), job);
                                }
                            }

                            //Autopromote if necessary
                            if (isAutopromote
                                    && existingTestDefinition != null
                                    && isAllocationOnlyChange(existingTestDefinition, testDefinitionToUpdate)) {
                                final boolean isQaPromoted;
                                job.log("allocation only change, checking against other branches for auto-promote capability for test " + testName + "\nat QA revision " + qaRevision + " and PRODUCTION revision " + prodRevision);
                                final boolean isQaPromotable = qaRevision != EnvironmentVersion.UNKNOWN_REVISION
                                        && isAllocationOnlyChange(getTestDefinition(Environment.QA, testName, qaRevision), testDefinitionToUpdate);
                                if (isQaPromotable) {
                                    job.log("auto-promoting changes to QA");
                                    isQaPromoted = doJobIndependentPromoteInternal(testName, username, password, Environment.WORKING, trunkStore.getLatestVersion(), Environment.QA, qaRevision, requestParameterMap, job, true);
                                } else {
                                    isQaPromoted = false;
                                    job.log("previous revision changes prevented auto-promote to QA");
                                }
                                if (isQaPromotable && isQaPromoted
                                        && prodRevision != EnvironmentVersion.UNKNOWN_REVISION
                                        && isAllocationOnlyChange(getTestDefinition(Environment.PRODUCTION, testName, prodRevision), testDefinitionToUpdate)) {
                                    job.log("auto-promoting changes to PRODUCTION");
                                    doJobIndependentPromoteInternal(testName, username, password, Environment.WORKING, trunkStore.getLatestVersion(), Environment.PRODUCTION, prodRevision, requestParameterMap, job, true);

                                } else {
                                    job.log("previous revision changes prevented auto-promote to PRODUCTION");
                                }
                            }

                            job.log("COMPLETE");
                            job.addUrl("/proctor/definition/" + UtilityFunctions.urlEncode(testName) + "?branch=" + theEnvironment.getName(), "View Result");
                            return true;
                        } catch (final GitNoAuthorizationException | GitNoDevelperAccessLevelException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Edit Failed: " + job.getTitle(), exp);
                        } catch (final StoreException.TestUpdateException exp) {
                            job.logFailedJob(exp);
                            LOGGER.error("Edit Failed: " + job.getTitle(), exp);
                        } catch (IncompatibleTestMatrixException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Edit Failed: " + job.getTitle(), exp);
                        } catch (IllegalArgumentException exp) {
                            job.logFailedJob(exp);
                            LOGGER.info("Edit Failed: " + job.getTitle(), exp);
                        } catch (Exception exp) {
                            job.logFailedJob(exp);
                            LOGGER.error("Edit Failed: " + job.getTitle(), exp);
                        }
                        return false;
                    }
                }
        );
    }

    public static boolean isValidTestName(String testName) {
        final Matcher m = VALID_TEST_NAME_PATTERN.matcher(testName);
        return m.matches();
    }

    public static boolean isValidBucketName(String bucketName) {
        final Matcher m = VALID_BUCKET_NAME_PATTERN.matcher(bucketName);
        return m.matches();
    }

     public static boolean isAllocationOnlyChange(final TestDefinition existingTestDefinition, final TestDefinition testDefinitionToUpdate) {
         final List<Allocation> existingAllocations = existingTestDefinition.getAllocations();
         final List<Allocation> allocationsToUpdate = testDefinitionToUpdate.getAllocations();
         final boolean nullRule = existingTestDefinition.getRule() == null;
         if (nullRule && testDefinitionToUpdate.getRule() != null) {
             return false;
         } else if (!nullRule && !existingTestDefinition.getRule().equals(testDefinitionToUpdate.getRule())) {
             return false;
         }
         if (!existingTestDefinition.getConstants().equals(testDefinitionToUpdate.getConstants())
            || !existingTestDefinition.getSpecialConstants().equals(testDefinitionToUpdate.getSpecialConstants())
            || !existingTestDefinition.getTestType().equals(testDefinitionToUpdate.getTestType())
            || !existingTestDefinition.getSalt().equals(testDefinitionToUpdate.getSalt())
            || !existingTestDefinition.getBuckets().equals(testDefinitionToUpdate.getBuckets())
            || existingAllocations.size()!=allocationsToUpdate.size())
            return false;

        /*
         * TestBucket .equals() override only checks name equality
         * loop below compares each attribute of a TestBucket
         */
        for (int i = 0; i<existingTestDefinition.getBuckets().size(); i++) {
            final TestBucket bucketOne = existingTestDefinition.getBuckets().get(i);
            final TestBucket bucketTwo = testDefinitionToUpdate.getBuckets().get(i);
            if (bucketOne == null) {
                if (bucketTwo != null) {
                    return false;
                }
            } else if (bucketTwo == null) {
                return false;
            } else {
                if (bucketOne.getValue() != bucketTwo.getValue()) {
                    return false;
                }
                final Payload payloadOne = bucketOne.getPayload();
                final Payload payloadTwo = bucketTwo.getPayload();
                if (payloadOne == null) {
                    if (payloadTwo != null) {
                        return false;
                    }
                } else if (!payloadOne.equals(payloadTwo)) {
                    return false;
                }
                if (bucketOne.getDescription() == null) {
                    if (bucketTwo.getDescription() != null) {
                        return false;
                    }
                } else if (!bucketOne.getDescription().equals(bucketTwo.getDescription())) {
                    return false;
                }
            }
        }

        /*
         * Comparing everything in an allocation except the lengths
         */
        for (int i = 0; i<existingAllocations.size(); i++) {
            final List<Range> existingAllocationRanges = existingAllocations.get(i).getRanges();
            final List<Range> allocationToUpdateRanges = allocationsToUpdate.get(i).getRanges();
            if (existingAllocations.get(i).getRule() == null && allocationsToUpdate.get(i).getRule() != null)
                return false;
            else if (existingAllocations.get(i).getRule() != null && !existingAllocations.get(i).getRule().equals(allocationsToUpdate.get(i).getRule()))
                return false;
            Map<Integer, Double> existingAllocRangeMap = generateAllocationRangeMap(existingAllocationRanges);
            Map<Integer, Double> allocToUpdateRangeMap = generateAllocationRangeMap(allocationToUpdateRanges);
            if (!existingAllocRangeMap.keySet().equals(allocToUpdateRangeMap.keySet())) {
                //An allocation was removed or added, do not autopromote
                return false;
            } else {
                for (Map.Entry<Integer, Double> entry : existingAllocRangeMap.entrySet()) {
                    final int bucketVal = entry.getKey();
                    final double existingLength = entry.getValue();
                    final double allocToUpdateLength = allocToUpdateRangeMap.get(bucketVal);
                    if (existingLength == 0 && allocToUpdateLength != 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static Map<Integer, Double> generateAllocationRangeMap(List<Range> ranges) {
        Map<Integer, Double> bucketToTotalAllocationMap = new HashMap<Integer, Double>();
        for (int aIndex = 0; aIndex < ranges.size(); aIndex++) {
            final int bucketVal = ranges.get(aIndex).getBucketValue();
            double sum = 0;
            if (bucketToTotalAllocationMap.containsKey(bucketVal)) {
                sum+=bucketToTotalAllocationMap.get(bucketVal);
            }
            sum+=ranges.get(aIndex).getLength();
            bucketToTotalAllocationMap.put(bucketVal, sum);
        }
        return bucketToTotalAllocationMap;
    }

    private String formatDefaultDeleteComment(final String testName, final String comment) {
        if (Strings.isNullOrEmpty(comment)) {
            return String.format("Deleting A/B test %s", testName);
        }
        return comment;
    }

    private String formatDefaultUpdateComment(final String testName, final String comment) {
        if (Strings.isNullOrEmpty(comment)) {
            return String.format("Updating A/B test %s", testName);
        }
        return comment;
    }

    private String formatDefaultCreateComment(final String testName, final String comment) {
        if (Strings.isNullOrEmpty(comment)) {
            return String.format("Creating A/B test %s", testName);
        }
        return comment;
    }


    private String formatFullComment(final String comment, final Map<String,String[]> requestParameterMap) {
        if (revisionCommitCommentFormatter != null) {
            return revisionCommitCommentFormatter.formatComment(comment, requestParameterMap);
        }
        else return comment.trim();
    }

    @RequestMapping(value = "/{testName}/verify", method = RequestMethod.GET)
    @ResponseBody
    public String doVerifyGet
        (
            @PathVariable String testName,
            @RequestParam(required = false) String src,
            @RequestParam(required = false) String srcRevision,
            @RequestParam(required = false) String dest,
            final HttpServletRequest request,
            final Model model
        ) {
        final Environment srcBranch = determineEnvironmentFromParameter(src);
        final Environment destBranch = determineEnvironmentFromParameter(dest);

        if (srcBranch == destBranch) {
            return "source == destination";
        }

        final TestDefinition d = getTestDefinition(srcBranch, testName, srcRevision);
        if (d == null) {
            return "could not find " + testName + " on " + srcBranch + " with revision " + srcRevision;
        }

        final CheckMatrixResult result = checkMatrix(destBranch, testName, d);
        if (result.isValid()) {
            return "check success";
        } else {
            return "failed: " + Joiner.on("\n").join(result.getErrors());
        }
    }

    @RequestMapping(value= "/{testName}/specification")
    public View doSpecificationGet(
            @PathVariable String testName,
            @RequestParam(required = false) final String branch
    ) {
        final Environment theEnvironment = determineEnvironmentFromParameter(branch);
        final ProctorStore store = determineStoreFromEnvironment(theEnvironment);

        final TestDefinition definition = getTestDefinition(store, testName);
        if (definition == null) {
            LOGGER.info("Unknown test definition : " + testName);
            // unknown testdefinition
            throw new NullPointerException("Unknown test definition");
        }

        JsonView view;
        try {
            final TestSpecification specification = ProctorUtils.generateSpecification(definition);
            view = new JsonView(specification);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Could not generate Test Specification", e);
            view = new JsonView(new JsonResponse(e.getMessage(), false, "Could not generate Test Specification"));
        }
        return view;
    }


    private CheckMatrixResult checkMatrix(final Environment checkAgainst,
                                          final String testName,
                                          final TestDefinition potential) {
        final TestMatrixVersion tmv = new TestMatrixVersion();
        tmv.setAuthor("author");
        tmv.setVersion("");
        tmv.setDescription("fake matrix for validation of " + testName);
        tmv.setPublished(new Date());

        final TestMatrixDefinition tmd = new TestMatrixDefinition();
        // The potential test definition will be null for test deletions
        if (potential != null) {
            tmd.setTests(ImmutableMap.<String, TestDefinition>of(testName, potential));
        }
        tmv.setTestMatrixDefinition(tmd);

        final TestMatrixArtifact artifact = ProctorUtils.convertToConsumableArtifact(tmv);
        // Verify
        final Map<AppVersion, Future<ProctorLoadResult>> futures = Maps.newLinkedHashMap();

        final Map<AppVersion, ProctorSpecification> toVerify = specificationSource.loadAllSuccessfulSpecifications(checkAgainst);
        for (Map.Entry<AppVersion, ProctorSpecification> entry : toVerify.entrySet()) {
            final AppVersion appVersion = entry.getKey();
            final ProctorSpecification specification = entry.getValue();
            futures.put(appVersion, verifierExecutor.submit(new Callable<ProctorLoadResult>() {
                @Override
                public ProctorLoadResult call() throws Exception {
                    LOGGER.info("Verifying artifact against : cached " + appVersion);
                    return verify(specification, artifact, testName, appVersion.toString());
                }
            }));

        }

        final ImmutableList.Builder<String> errorsBuilder = ImmutableList.builder();
        while (!futures.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (final InterruptedException e) {
                LOGGER.error("Oh heavens", e);
            }
            for (final Iterator<Map.Entry<AppVersion, Future<ProctorLoadResult>>> iterator = futures.entrySet().iterator(); iterator.hasNext(); ) {
                final Map.Entry<AppVersion, Future<ProctorLoadResult>> entry = iterator.next();
                final AppVersion version = entry.getKey();
                final Future<ProctorLoadResult> future = entry.getValue();
                if (future.isDone()) {
                    iterator.remove();
                    try {
                        final ProctorLoadResult proctorLoadResult = future.get();
                        if (proctorLoadResult.hasInvalidTests()) {
                            errorsBuilder.add(getErrorMessage(version, proctorLoadResult));
                        }
                    } catch (final InterruptedException e) {
                        errorsBuilder.add(version.toString() + " failed. " + e.getMessage());
                        LOGGER.error("Interrupted getting " + version, e);
                    } catch (final ExecutionException e) {
                        final Throwable cause = e.getCause();
                        errorsBuilder.add(version.toString() + " failed. " + cause.getMessage());
                        LOGGER.error("Unable to verify " + version, cause);
                    }
                }
            }
        }

        final ImmutableList<String> errors = errorsBuilder.build();
        final boolean greatSuccess = errors.isEmpty();

        return new CheckMatrixResult(greatSuccess, errors);
    }

    private static String getErrorMessage(final AppVersion appVersion, final ProctorLoadResult proctorLoadResult) {
        final Map<String, IncompatibleTestMatrixException> testsWithErrors = proctorLoadResult.getTestErrorMap();
        final Set<String> missingTests = proctorLoadResult.getMissingTests();

        // We expect at most one test to have a problem because we limited the verification to a single test
        if (testsWithErrors.size() > 0) {
            return testsWithErrors.values().iterator().next().getMessage();
        } else if (missingTests.size() > 0) {
            return String.format("%s requires test '%s'", appVersion, missingTests.iterator().next());
        } else {
            return "";
        }
    }

    private ProctorLoadResult verify(final ProctorSpecification spec,
                                     final TestMatrixArtifact testMatrix,
                                     final String testName,
                                     final String matrixSource) {
        final Map<String, TestSpecification> requiredTests;
        if (spec.getTests().containsKey(testName)) {
            requiredTests = ImmutableMap.of(testName, spec.getTests().get(testName));
        } else {
            requiredTests = Collections.emptyMap();
        }
        return ProctorUtils.verify(testMatrix, matrixSource, requiredTests);
    }

    private static void validateUsernamePassword(String username, String password) throws IllegalArgumentException {
        if (CharMatcher.WHITESPACE.matchesAllOf(Strings.nullToEmpty(username)) || CharMatcher.WHITESPACE.matchesAllOf(Strings.nullToEmpty(password))) {
            throw new IllegalArgumentException("No username or password provided");
        }
    }



    private void validateComment(String comment) throws IllegalArgumentException {
        if (CharMatcher.WHITESPACE.matchesAllOf(Strings.nullToEmpty(comment))) {
            throw new IllegalArgumentException("Comment is required.");
        }
    }

    private void validateBasicInformation(final TestDefinition definition,
                                          final BackgroundJob backgroundJob) throws IllegalArgumentException {
        if (CharMatcher.WHITESPACE.matchesAllOf(Strings.nullToEmpty(definition.getDescription()))) {
            throw new IllegalArgumentException("Description is required.");
        }
        if (CharMatcher.WHITESPACE.matchesAllOf(Strings.nullToEmpty(definition.getSalt()))) {
            throw new IllegalArgumentException("Salt is required.");
        }
        if (definition.getTestType() == null) {
            throw new IllegalArgumentException("TestType is required.");
        }

        if (definition.getBuckets().isEmpty()) {
            throw new IllegalArgumentException("Buckets cannot be empty.");
        }

        if (definition.getAllocations().isEmpty()) {
            throw new IllegalArgumentException("Allocations cannot be empty.");
        }

        validateAllocationsAndBuckets(definition, backgroundJob);
    }

    private void validateAllocationsAndBuckets(final TestDefinition definition, final BackgroundJob backgroundJob) throws IllegalArgumentException {
        final Allocation allocation = definition.getAllocations().get(0);
        final List<Range> ranges = allocation.getRanges();
        final TestType testType = definition.getTestType();
        final int controlBucketValue = 0;
        final double DELTA = 1E-6;

        final Map<Integer, Double> totalTestAllocationMap = new HashMap<Integer, Double>();
        for (Range range : ranges) {
            final int bucketValue = range.getBucketValue();
            double bucketAllocation = range.getLength();
            if (totalTestAllocationMap.containsKey(bucketValue)) {
                bucketAllocation += totalTestAllocationMap.get(bucketValue);
            }
            totalTestAllocationMap.put(bucketValue, bucketAllocation);
        }

        final boolean hasControlBucket = totalTestAllocationMap.containsKey(controlBucketValue);
        /* The number of buckets with allocation greater than zero */
        int numActiveBuckets = 0;

        for (Integer bucketValue : totalTestAllocationMap.keySet()) {
            final double totalBucketAllocation = totalTestAllocationMap.get(bucketValue);
            if(totalBucketAllocation > 0) {
                numActiveBuckets++;
            }
        }

        /* if there are 2 buckets with positive allocations, test and control buckets
            should be the same size
        */
        if(numActiveBuckets > 1 && hasControlBucket) {
            final double totalControlBucketAllocation = totalTestAllocationMap.get(controlBucketValue);
            for (Integer bucketValue : totalTestAllocationMap.keySet()) {
                final double totalBucketAllocation = totalTestAllocationMap.get(bucketValue);
                if (totalBucketAllocation > 0) {
                    numActiveBuckets++;
                }
                final double difference = totalBucketAllocation - totalControlBucketAllocation;
                if (bucketValue > 0 && totalBucketAllocation > 0 && Math.abs(difference) >= DELTA) {
                    backgroundJob.log("WARNING: Positive bucket total allocation size not same as control bucket total allocation size. \nBucket #" + bucketValue + "=" + totalBucketAllocation + ", Zero Bucket=" + totalControlBucketAllocation);
                }
            }
        }

        /* If there are 2 buckets with positive allocations, one should be control */
        if (numActiveBuckets > 1 && !hasControlBucket) {
            backgroundJob.log("WARNING: You should have a zero bucket (control).");
        }

        for (TestBucket bucket : definition.getBuckets()) {
            if (testType == TestType.PAGE && bucket.getValue() < 0) {
                throw new IllegalArgumentException("PAGE tests cannot contain negative buckets.");
            }
        }

        for (TestBucket bucket : definition.getBuckets()) {
            final String name = bucket.getName();
            if (!isValidBucketName(name)) {
                throw new IllegalArgumentException("Bucket name must be alpha-numeric underscore and not start with a number, found: '" + name + "'");
            }
        }
    }



    private String doView(final Environment b,
                          final Views view,
                          final String testName,
                          // TODO (parker) 7/27/12 - add Revisioned (that has Revision + testName)
                          final TestDefinition definition,
                          final List<RevisionDefinition> history,
                          final EnvironmentVersion version,
                          Model model) {
        model.addAttribute("testName", testName);
        model.addAttribute("testDefinition", definition);
        model.addAttribute("isCreate", view == Views.CREATE);
        model.addAttribute("branch", b);
        model.addAttribute("version", version);

        final Map<String, Object> specialConstants;
        if (definition.getSpecialConstants() != null) {
            specialConstants = definition.getSpecialConstants();
        } else {
            specialConstants = Collections.<String, Object>emptyMap();
        }
        model.addAttribute("specialConstants", specialConstants);

        model.addAttribute("session",
                           SessionViewModel.builder()
                               .setUseCompiledCSS(getConfiguration().isUseCompiledCSS())
                               .setUseCompiledJavaScript(getConfiguration().isUseCompiledJavaScript())
                                   // todo get the appropriate js compile / non-compile url
                               .build());

        boolean emptyClients = true;
        for (final Environment environment : Environment.values()) {
            emptyClients &= specificationSource.loadAllSpecifications(environment).keySet().isEmpty();
        }
        model.addAttribute("emptyClients", emptyClients);

        final Set<AppVersion> devApplications = specificationSource.activeClients(Environment.WORKING, testName);
        model.addAttribute("devApplications", devApplications);
        final Set<AppVersion> qaApplications = specificationSource.activeClients(Environment.QA, testName);
        model.addAttribute("qaApplications", qaApplications);
        final Set<AppVersion> productionApplications = specificationSource.activeClients(Environment.PRODUCTION, testName);
        model.addAttribute("productionApplications", productionApplications);

        try {
            // convert to artifact?
            final StringWriter sw = new StringWriter();
            ProctorUtils.serializeTestDefinition(sw, definition);
            model.addAttribute("testDefinitionJson", sw.toString());
        } catch (JsonGenerationException e) {
            LOGGER.error("Could not generate JSON", e);
        } catch (JsonMappingException e) {
            LOGGER.error("Could not generate JSON", e);
        } catch (IOException e) {
            LOGGER.error("Could not generate JSON", e);
        }

        try {
            final StringWriter swSpecification = new StringWriter();
            ProctorUtils.serializeTestSpecification(swSpecification, ProctorUtils.generateSpecification(definition));
            model.addAttribute("testSpecificationJson", swSpecification.toString());
        } catch (IllegalArgumentException e) {
            LOGGER.error("Could not generate Test Specification", e);
        } catch (JsonGenerationException e) {
            LOGGER.error("Could not generate JSON", e);
        } catch (JsonMappingException e) {
            LOGGER.error("Could not generate JSON", e);
        } catch (IOException e) {
            LOGGER.error("Could not generate JSON", e);
        }

        model.addAttribute("testDefinitionHistory", history);
        final Revision testDefinitionVersion = version == null ? EnvironmentVersion.FULL_UNKNOWN_REVISION : version.getFullRevision(b);
        model.addAttribute("testDefinitionVersion", testDefinitionVersion);

        // TODO (parker) 8/9/12 - Add common model for TestTypes and other Drop Downs
        model.addAttribute("testTypes", Arrays.asList(TestType.values()));

        return view.getName();
    }


    /**
     * This needs to be moved to a separate checker class implementing some interface
     */
    private URL getSpecificationUrl(final ProctorClientApplication client) {
        final String urlStr = client.getBaseApplicationUrl() + "/private/proctor/specification";
        try {
            return new URL(urlStr);
        } catch (final MalformedURLException e) {
            throw new RuntimeException("Somehow created a malformed URL: " + urlStr, e);
        }
    }


    // @Nullable
    private static TestDefinition getTestDefinition(final ProctorStore store, final String testName) {
        try {
            return store.getCurrentTestDefinition(testName);
        } catch (StoreException e) {
            LOGGER.info("Failed to get current test definition for: " + testName, e);
            return null;
        }
    }

    // @Nullable
    private static TestDefinition getTestDefinition(final ProctorStore store, final String testName, final String revision) {
        try {
            if ("-1".equals(revision)){
                LOGGER.info("Ignore revision id -1");
                return null;
            }
            return store.getTestDefinition(testName, revision);
        } catch (StoreException e) {
            LOGGER.info("Failed to get current test definition for: " + testName, e);
            return null;
        }
    }

    private TestDefinition getTestDefinition(final Environment environment, final String testName, final String revision) {
        final ProctorStore store = determineStoreFromEnvironment(environment);
        final EnvironmentVersion version = promoter.getEnvironmentVersion(testName);
        final String environmentVersion = version.getRevision(environment);
        if (revision.isEmpty() ||
                (!"-1".equals(environmentVersion) && revision.equals(environmentVersion))) {
            // if revision is environment latest version, fetching current environment version is more cache-friendly
            return getTestDefinition(store, testName);
        } else {
            return getTestDefinition(store, testName, revision);
        }
    }

    private static List<Revision> getTestHistory(final ProctorStore store, final String testName, final int limit) {
        return getTestHistory(store, testName, null, limit);
    }

    private static List<Revision> getTestHistory(final ProctorStore store, final String testName, final String startRevision) {
        return getTestHistory(store, testName, startRevision, Integer.MAX_VALUE);
    }

    // @Nonnull
    private static List<Revision> getTestHistory(final ProctorStore store,
                                                 final String testName,
                                                 final String startRevision,
                                                 final int limit) {
        try {
            final List<Revision> history;
            if (startRevision == null) {
                history = store.getHistory(testName, 0, limit);
            } else {
                history = store.getHistory(testName, startRevision, 0, limit);
            }
            if (history.size() == 0) {
                LOGGER.info("No version history for [" + testName + "]");
            }
            return history;
        } catch (StoreException e) {
            LOGGER.info("Failed to get current test history for: " + testName, e);
            return null;
        }
    }

    private static class CheckMatrixResult {
        final boolean isValid;
        final List<String> errors;

        private CheckMatrixResult(boolean valid, List<String> errors) {
            isValid = valid;
            this.errors = errors;
        }

        public boolean isValid() {
            return isValid;
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
