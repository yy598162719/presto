/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.execution;

import com.facebook.airlift.concurrent.SetThreadName;
import com.facebook.presto.Session;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.execution.QueryPreparer.PreparedQuery;
import com.facebook.presto.execution.StateMachine.StateChangeListener;
import com.facebook.presto.execution.buffer.OutputBuffers;
import com.facebook.presto.execution.buffer.OutputBuffers.OutputBufferId;
import com.facebook.presto.execution.scheduler.ExecutionPolicy;
import com.facebook.presto.execution.scheduler.LegacySqlQueryScheduler;
import com.facebook.presto.execution.scheduler.SectionExecutionFactory;
import com.facebook.presto.execution.scheduler.SplitSchedulerStats;
import com.facebook.presto.execution.scheduler.SqlQueryScheduler;
import com.facebook.presto.execution.scheduler.SqlQuerySchedulerInterface;
import com.facebook.presto.memory.VersionedMemoryPoolId;
import com.facebook.presto.metadata.InternalNodeManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.security.AccessControl;
import com.facebook.presto.server.BasicQueryInfo;
import com.facebook.presto.spi.ConnectorId;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.TableHandle;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.PlanNodeIdAllocator;
import com.facebook.presto.spi.resourceGroups.QueryType;
import com.facebook.presto.spi.resourceGroups.ResourceGroupQueryLimits;
import com.facebook.presto.split.CloseableSplitSourceProvider;
import com.facebook.presto.split.SplitManager;
import com.facebook.presto.split.SplitSourceProvider;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Analyzer;
import com.facebook.presto.sql.analyzer.QueryExplainer;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.planner.InputExtractor;
import com.facebook.presto.sql.planner.LogicalPlanner;
import com.facebook.presto.sql.planner.OutputExtractor;
import com.facebook.presto.sql.planner.PartitioningHandle;
import com.facebook.presto.sql.planner.Plan;
import com.facebook.presto.sql.planner.PlanFragmenter;
import com.facebook.presto.sql.planner.PlanOptimizers;
import com.facebook.presto.sql.planner.PlanVariableAllocator;
import com.facebook.presto.sql.planner.SplitSourceFactory;
import com.facebook.presto.sql.planner.SubPlan;
import com.facebook.presto.sql.planner.optimizations.PlanOptimizer;
import com.facebook.presto.sql.planner.plan.OutputNode;
import com.facebook.presto.sql.planner.sanity.PlanChecker;
import com.facebook.presto.sql.tree.Explain;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import org.joda.time.DateTime;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.facebook.presto.SystemSessionProperties.isSpoolingOutputBufferEnabled;
import static com.facebook.presto.SystemSessionProperties.isUseLegacyScheduler;
import static com.facebook.presto.common.RuntimeMetricName.FRAGMENT_PLAN_TIME_NANOS;
import static com.facebook.presto.common.RuntimeMetricName.LOGICAL_PLANNER_TIME_NANOS;
import static com.facebook.presto.execution.buffer.OutputBuffers.BROADCAST_PARTITION_ID;
import static com.facebook.presto.execution.buffer.OutputBuffers.createInitialEmptyOutputBuffers;
import static com.facebook.presto.execution.buffer.OutputBuffers.createSpoolingOutputBuffers;
import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.airlift.units.DataSize.succinctBytes;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;

@ThreadSafe
public class SqlQueryExecution
        implements QueryExecution
{
    private static final OutputBufferId OUTPUT_BUFFER_ID = new OutputBufferId(0);

    private final QueryStateMachine stateMachine;
    private final String slug;
    private final int retryCount;
    private final Metadata metadata;
    private final SqlParser sqlParser;
    private final SplitManager splitManager;
    private final List<PlanOptimizer> planOptimizers;
    private final List<PlanOptimizer> runtimePlanOptimizers;
    private final PlanFragmenter planFragmenter;
    private final RemoteTaskFactory remoteTaskFactory;
    private final LocationFactory locationFactory;
    private final ExecutorService queryExecutor;
    private final SectionExecutionFactory sectionExecutionFactory;
    private final InternalNodeManager internalNodeManager;

    private final AtomicReference<SqlQuerySchedulerInterface> queryScheduler = new AtomicReference<>();
    private final AtomicReference<Plan> queryPlan = new AtomicReference<>();
    private final ExecutionPolicy executionPolicy;
    private final SplitSchedulerStats schedulerStats;
    private final Analysis analysis;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final PlanChecker planChecker;
    private final PlanNodeIdAllocator idAllocator = new PlanNodeIdAllocator();
    private final AtomicReference<PlanVariableAllocator> variableAllocator = new AtomicReference<>();
    private final PartialResultQueryManager partialResultQueryManager;
    private final AtomicReference<Optional<ResourceGroupQueryLimits>> resourceGroupQueryLimits = new AtomicReference<>(Optional.empty());

    private SqlQueryExecution(
            PreparedQuery preparedQuery,
            QueryStateMachine stateMachine,
            String slug,
            int retryCount,
            Metadata metadata,
            AccessControl accessControl,
            SqlParser sqlParser,
            SplitManager splitManager,
            List<PlanOptimizer> planOptimizers,
            List<PlanOptimizer> runtimePlanOptimizers,
            PlanFragmenter planFragmenter,
            RemoteTaskFactory remoteTaskFactory,
            LocationFactory locationFactory,
            ExecutorService queryExecutor,
            SectionExecutionFactory sectionExecutionFactory,
            InternalNodeManager internalNodeManager,
            QueryExplainer queryExplainer,
            ExecutionPolicy executionPolicy,
            SplitSchedulerStats schedulerStats,
            StatsCalculator statsCalculator,
            CostCalculator costCalculator,
            WarningCollector warningCollector,
            PlanChecker planChecker,
            PartialResultQueryManager partialResultQueryManager)
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            this.slug = requireNonNull(slug, "slug is null");
            this.retryCount = retryCount;
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
            this.splitManager = requireNonNull(splitManager, "splitManager is null");
            this.planOptimizers = requireNonNull(planOptimizers, "planOptimizers is null");
            this.runtimePlanOptimizers = requireNonNull(runtimePlanOptimizers, "runtimePlanOptimizers is null");
            this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
            this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
            this.queryExecutor = requireNonNull(queryExecutor, "queryExecutor is null");
            this.sectionExecutionFactory = requireNonNull(sectionExecutionFactory, "sectionExecutionFactory is null");
            this.internalNodeManager = requireNonNull(internalNodeManager, "internalNodeManager is null");
            this.executionPolicy = requireNonNull(executionPolicy, "executionPolicy is null");
            this.schedulerStats = requireNonNull(schedulerStats, "schedulerStats is null");
            this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
            this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
            this.stateMachine = requireNonNull(stateMachine, "stateMachine is null");
            this.planChecker = requireNonNull(planChecker, "planChecker is null");

            // analyze query
            requireNonNull(preparedQuery, "preparedQuery is null");

            stateMachine.beginSemanticAnalyzing();
            Analyzer analyzer = new Analyzer(
                    stateMachine.getSession(),
                    metadata,
                    sqlParser,
                    accessControl,
                    Optional.of(queryExplainer),
                    preparedQuery.getParameters(),
                    warningCollector);

            this.analysis = analyzer.analyzeSemantic(preparedQuery.getStatement(), false);
            stateMachine.setUpdateType(analysis.getUpdateType());
            stateMachine.setExpandedQuery(analysis.getExpandedQuery());

            stateMachine.beginColumnAccessPermissionChecking();
            analyzer.checkColumnAccessPermissions(this.analysis);
            stateMachine.endColumnAccessPermissionChecking();

            // when the query finishes cache the final query info, and clear the reference to the output stage
            AtomicReference<SqlQuerySchedulerInterface> queryScheduler = this.queryScheduler;
            stateMachine.addStateChangeListener(state -> {
                if (!state.isDone()) {
                    return;
                }

                // query is now done, so abort any work that is still running
                SqlQuerySchedulerInterface scheduler = queryScheduler.get();
                if (scheduler != null) {
                    scheduler.abort();
                }
            });

            this.remoteTaskFactory = new TrackingRemoteTaskFactory(requireNonNull(remoteTaskFactory, "remoteTaskFactory is null"), stateMachine);
            this.partialResultQueryManager = requireNonNull(partialResultQueryManager, "partialResultQueryManager is null");
        }
    }

    @Override
    public String getSlug()
    {
        return slug;
    }

    @Override
    public int getRetryCount()
    {
        return retryCount;
    }

    @Override
    public VersionedMemoryPoolId getMemoryPool()
    {
        return stateMachine.getMemoryPool();
    }

    @Override
    public void setMemoryPool(VersionedMemoryPoolId poolId)
    {
        stateMachine.setMemoryPool(poolId);
    }

    @Override
    public DataSize getUserMemoryReservation()
    {
        // acquire reference to scheduler before checking finalQueryInfo, because
        // state change listener sets finalQueryInfo and then clears scheduler when
        // the query finishes.
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getUserMemoryReservation();
        }
        if (scheduler == null) {
            return new DataSize(0, BYTE);
        }
        return succinctBytes(scheduler.getUserMemoryReservation());
    }

    @Override
    public DataSize getTotalMemoryReservation()
    {
        // acquire reference to scheduler before checking finalQueryInfo, because
        // state change listener sets finalQueryInfo and then clears scheduler when
        // the query finishes.
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getTotalMemoryReservation();
        }
        if (scheduler == null) {
            return new DataSize(0, BYTE);
        }
        return succinctBytes(scheduler.getTotalMemoryReservation());
    }

    @Override
    public DateTime getCreateTime()
    {
        return stateMachine.getCreateTime();
    }

    @Override
    public Optional<DateTime> getExecutionStartTime()
    {
        return stateMachine.getExecutionStartTime();
    }

    @Override
    public DateTime getLastHeartbeat()
    {
        return stateMachine.getLastHeartbeat();
    }

    @Override
    public Optional<DateTime> getEndTime()
    {
        return stateMachine.getEndTime();
    }

    @Override
    public Duration getTotalCpuTime()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getTotalCpuTime();
        }
        if (scheduler == null) {
            return new Duration(0, SECONDS);
        }
        return scheduler.getTotalCpuTime();
    }

    @Override
    public DataSize getRawInputDataSize()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getRawInputDataSize();
        }
        if (scheduler == null) {
            return new DataSize(0, BYTE);
        }
        return scheduler.getRawInputDataSize();
    }

    @Override
    public DataSize getOutputDataSize()
    {
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        Optional<QueryInfo> finalQueryInfo = stateMachine.getFinalQueryInfo();
        if (finalQueryInfo.isPresent()) {
            return finalQueryInfo.get().getQueryStats().getOutputDataSize();
        }
        if (scheduler == null) {
            return new DataSize(0, BYTE);
        }
        return scheduler.getOutputDataSize();
    }

    @Override
    public BasicQueryInfo getBasicQueryInfo()
    {
        return stateMachine.getFinalQueryInfo()
                .map(BasicQueryInfo::new)
                .orElseGet(() -> stateMachine.getBasicQueryInfo(Optional.ofNullable(queryScheduler.get()).map(SqlQuerySchedulerInterface::getBasicStageStats)));
    }

    @Override
    public int getRunningTaskCount()
    {
        return stateMachine.getCurrentRunningTaskCount();
    }

    @Override
    public void start()
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            try {
                // transition to planning
                if (!stateMachine.transitionToPlanning()) {
                    // query already started or finished
                    return;
                }

                // analyze query
                // sql分析与执行计划分析 重点方法
                PlanRoot plan = analyzeQuery();

                metadata.beginQuery(getSession(), plan.getConnectors());

                // plan distribution of query
                // 生成数据源Connector的Connector，创建SqlStageExecution（Stage）、指定StageScheduler。重点
                planDistribution(plan);
                // 看到这里可以查看ConnectorSplit类的切片信息
                // 这一步只是生成数据源的Split，但是既不会把Split安排到某个Presto Worker上，也不会去真正的使用Split读取Connector的数据.
                // 感兴趣可以看 SplitManager::getSplits() 与 ConnectorSplitManager::getSplit() 的源码
                // transition to starting
                if (!stateMachine.transitionToStarting()) {
                    // query already started or finished
                    return;
                }

                // if query is not finished, start the scheduler, otherwise cancel it
                SqlQuerySchedulerInterface scheduler = queryScheduler.get();
                // Stage的调度，根据执行计划，将Task调度到Presto Worker上 重点
                if (!stateMachine.isDone()) {
                    scheduler.start();
                }
            }
            catch (Throwable e) {
                fail(e);
                throwIfInstanceOf(e, Error.class);
            }
        }
    }

    @Override
    public void addStateChangeListener(StateChangeListener<QueryState> stateChangeListener)
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            stateMachine.addStateChangeListener(stateChangeListener);
        }
    }

    @Override
    public Optional<ResourceGroupQueryLimits> getResourceGroupQueryLimits()
    {
        return resourceGroupQueryLimits.get();
    }

    @Override
    public void setResourceGroupQueryLimits(ResourceGroupQueryLimits resourceGroupQueryLimits)
    {
        if (!this.resourceGroupQueryLimits.compareAndSet(Optional.empty(), Optional.of(requireNonNull(resourceGroupQueryLimits, "resourceGroupQueryLimits is null")))) {
            throw new IllegalStateException("Cannot set resourceGroupQueryLimits more than once");
        }
    }

    @Override
    public Session getSession()
    {
        return stateMachine.getSession();
    }

    @Override
    public void addFinalQueryInfoListener(StateChangeListener<QueryInfo> stateChangeListener)
    {
        stateMachine.addQueryInfoStateChangeListener(stateChangeListener);
    }

    private PlanRoot analyzeQuery()
    {
        try {
            return doAnalyzeQuery();
        }
        catch (StackOverflowError e) {
            throw new PrestoException(NOT_SUPPORTED, "statement is too large (stack overflow during analysis)", e);
        }
    }

    private PlanRoot doAnalyzeQuery()
    {
        // time analysis phase
        stateMachine.beginAnalysis();

        // plan query
        // 生成执行计划 第四步：【Coordinator】语义分析(Analysis)、生成执行计划LogicalPlan
        /**
         * 生成执行计划LogicalPlan：生成以PlanNode为节点的逻辑执行计划，它也是类似于AST那样的树形结构，树节点和根的类型都是PlanNode
         * 。其实在Presto代码中，并没有任何一段代码将PlanNode树称之为逻辑执行计划（LogicalPlan），但是由于负责生成PlanNode树的类名称是LogicalPlanner
         * ，所以我们也称之为逻辑执行计划（LogicalPlan），此PlanNode树的实际作用，也与其他SQL执行引擎的逻辑执行计划完全相同。
         */
        LogicalPlanner logicalPlanner = new LogicalPlanner(false, stateMachine.getSession(), planOptimizers, idAllocator, metadata, sqlParser, statsCalculator, costCalculator, stateMachine.getWarningCollector(), planChecker);
        Plan plan = getSession().getRuntimeStats().profileNanos(
                LOGICAL_PLANNER_TIME_NANOS,
                // 第五步：【Coordinator】优化执行计划，生成Optimized Logical Plan
                // 优化执行计划，生成Optimized Logical Plan：用预定义的几百个优化器迭代优化之前生成的PlanNode树
                // ，并返回优化后的PlanNode树。后面小节再详细介绍。
                () -> logicalPlanner.plan(analysis));
        queryPlan.set(plan);

        // extract inputs
        List<Input> inputs = new InputExtractor(metadata, stateMachine.getSession()).extractInputs(plan.getRoot());
        stateMachine.setInputs(inputs);

        // extract output
        Optional<Output> output = new OutputExtractor().extractOutput(plan.getRoot());
        stateMachine.setOutput(output);

        // fragment the plan
        // the variableAllocator is finally passed to SqlQueryScheduler for runtime cost-based optimizations
        variableAllocator.set(new PlanVariableAllocator(plan.getTypes().allVariables()));
        SubPlan fragmentedPlan = getSession().getRuntimeStats().profileNanos(
                FRAGMENT_PLAN_TIME_NANOS,
                // 第六步：【Coordinator】为逻辑执行计划分段(Fragment)[也被称之为划分Stage，它们之间是一一对应的] 重点
                // StageExecution负责生成的Task在任务调度时，会被分发到Presto Worker上执行。这些Task执行的是什么逻辑？这个就是由Task所属的StageExecution对应PlanFragment中的执行计划(PlanNode树）决定的。
                // 至此，第四步到第六步所做的工作，它们都被包装在SqlQueryExecution::doAnalyzeQuery()中，回到上层的planDistribution方法
                () -> planFragmenter.createSubPlans(stateMachine.getSession(), plan, false, idAllocator, variableAllocator.get(), stateMachine.getWarningCollector()));

        // record analysis time
        stateMachine.endAnalysis();

        boolean explainAnalyze = analysis.getStatement() instanceof Explain && ((Explain) analysis.getStatement()).isAnalyze();
        return new PlanRoot(fragmentedPlan, !explainAnalyze, extractConnectors(analysis));
    }

    private static Set<ConnectorId> extractConnectors(Analysis analysis)
    {
        ImmutableSet.Builder<ConnectorId> connectors = ImmutableSet.builder();

        for (TableHandle tableHandle : analysis.getTables()) {
            connectors.add(tableHandle.getConnectorId());
        }

        if (analysis.getInsert().isPresent()) {
            TableHandle target = analysis.getInsert().get().getTarget();
            connectors.add(target.getConnectorId());
        }

        return connectors.build();
    }

    /**
     * planDistribution()的主要逻辑就两个：
     *一个是从数据源Connector中获取到所有的Split。Split是什么呢？你可以理解为它是你要从数据源获取的数据分片，这是Presto中分块组织数据的方式
     * ，Presto Connector会将待处理的所有数据划分为若干分片让Presto读取，而这些分片也会被安排到（assign）到多个Presto Worker上来处理以实现分布式高性能计算。
     *
     *另一是createSqlQueryScheduler()会为执行计划的每一个PlanFragment创建一个SqlStageExecution。每个SqlStageExecution（Stage）对应一个StageScheduler
     * ，不同分区类型(PartitioningHandle)的Stage PlanFragment对应不同类型的StageScheduler，后面在调度Stage时，主要依赖的是这个StageScheduler的实现。
     * @param plan
     */
    private void planDistribution(PlanRoot plan)
    {
        SplitSourceProvider delegate=splitManager::getSplits;
        CloseableSplitSourceProvider splitSourceProvider = new CloseableSplitSourceProvider(delegate);

        // ensure split sources are closed
        stateMachine.addStateChangeListener(state -> {
            if (state.isDone()) {
                splitSourceProvider.close();
            }
        });

        // if query was canceled, skip creating scheduler
        if (stateMachine.isDone()) {
            return;
        }

        SubPlan outputStagePlan = plan.getRoot();

        // record output field
        stateMachine.setColumns(((OutputNode) outputStagePlan.getFragment().getRoot()).getColumnNames(), outputStagePlan.getFragment().getTypes());

        // 创建最后一个Stage的OutputBuffer（代码叫Root，因为最后一个Stage其实就是在执行计划树的树根），这个OutputBuffer用于给Presto SQL客户端输出Query的最终计算结果。
        PartitioningHandle partitioningHandle = outputStagePlan.getFragment().getPartitioningScheme().getPartitioning().getHandle();
        OutputBuffers rootOutputBuffers;
        if (isSpoolingOutputBufferEnabled(getSession())) {
            rootOutputBuffers = createSpoolingOutputBuffers();
        }
        else {
            rootOutputBuffers = createInitialEmptyOutputBuffers(partitioningHandle)
                    .withBuffer(OUTPUT_BUFFER_ID, BROADCAST_PARTITION_ID)
                    .withNoMoreBufferIds();
        }

        SplitSourceFactory splitSourceFactory = new SplitSourceFactory(splitSourceProvider, stateMachine.getWarningCollector());
        // 创建SqlStageExecution（俗称Stage），被包装在SqlQueryScheduler里面返回，我们在前面说过，Stage与PlanFragment是一一对应的
        // 。这里只是创建Stage，但是不会去调度执行它，这个动作在后面。
        // 创建完SqlStageExecution后，会被包装在新创建的SqlQueryScheduler对象中返回，紧接着就是去调度Stage、创建Task，分发到Presto集群的Worker上去执行
        // build the stage execution objects (this doesn't schedule execution)
        SqlQuerySchedulerInterface scheduler = isUseLegacyScheduler(getSession()) ?
                LegacySqlQueryScheduler.createSqlQueryScheduler(
                        locationFactory,
                        executionPolicy,
                        queryExecutor,
                        schedulerStats,
                        sectionExecutionFactory,
                        remoteTaskFactory,
                        splitSourceFactory,
                        stateMachine.getSession(),
                        metadata.getFunctionAndTypeManager(),
                        stateMachine,
                        outputStagePlan,
                        rootOutputBuffers,
                        plan.isSummarizeTaskInfos(),
                        runtimePlanOptimizers,
                        stateMachine.getWarningCollector(),
                        idAllocator,
                        variableAllocator.get(),
                        planChecker,
                        metadata,
                        sqlParser,
                        partialResultQueryManager) :
                SqlQueryScheduler.createSqlQueryScheduler(
                        locationFactory,
                        executionPolicy,
                        queryExecutor,
                        schedulerStats,
                        sectionExecutionFactory,
                        remoteTaskFactory,
                        splitSourceFactory,
                        internalNodeManager,
                        stateMachine.getSession(),
                        stateMachine,
                        outputStagePlan,
                        plan.isSummarizeTaskInfos(),
                        metadata.getFunctionAndTypeManager(),
                        runtimePlanOptimizers,
                        stateMachine.getWarningCollector(),
                        idAllocator,
                        variableAllocator.get(),
                        planChecker,
                        metadata,
                        sqlParser,
                        partialResultQueryManager);

        queryScheduler.set(scheduler);

        // if query was canceled during scheduler creation, abort the scheduler
        // directly since the callback may have already fired
        if (stateMachine.isDone()) {
            scheduler.abort();
            queryScheduler.set(null);
        }
    }

    @Override
    public void cancelQuery()
    {
        stateMachine.transitionToCanceled();
    }

    @Override
    public void cancelStage(StageId stageId)
    {
        requireNonNull(stageId, "stageId is null");

        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            SqlQuerySchedulerInterface scheduler = queryScheduler.get();
            if (scheduler != null) {
                scheduler.cancelStage(stageId);
            }
        }
    }

    @Override
    public void fail(Throwable cause)
    {
        requireNonNull(cause, "cause is null");

        stateMachine.transitionToFailed(cause);

        // acquire reference to scheduler before checking finalQueryInfo, because
        // state change listener sets finalQueryInfo and then clears scheduler when
        // the query finishes.
        SqlQuerySchedulerInterface scheduler = queryScheduler.get();
        stateMachine.updateQueryInfo(Optional.ofNullable(scheduler).map(SqlQuerySchedulerInterface::getStageInfo));
    }

    @Override
    public boolean isDone()
    {
        return getState().isDone();
    }

    @Override
    public void addOutputInfoListener(Consumer<QueryOutputInfo> listener)
    {
        stateMachine.addOutputInfoListener(listener);
    }

    @Override
    public ListenableFuture<QueryState> getStateChange(QueryState currentState)
    {
        return stateMachine.getStateChange(currentState);
    }

    @Override
    public void recordHeartbeat()
    {
        stateMachine.recordHeartbeat();
    }

    @Override
    public void pruneInfo()
    {
        stateMachine.pruneQueryInfo();
    }

    @Override
    public QueryId getQueryId()
    {
        return stateMachine.getQueryId();
    }

    @Override
    public QueryInfo getQueryInfo()
    {
        try (SetThreadName ignored = new SetThreadName("Query-%s", stateMachine.getQueryId())) {
            // acquire reference to scheduler before checking finalQueryInfo, because
            // state change listener sets finalQueryInfo and then clears scheduler when
            // the query finishes.
            SqlQuerySchedulerInterface scheduler = queryScheduler.get();

            return stateMachine.getFinalQueryInfo().orElseGet(() -> buildQueryInfo(scheduler));
        }
    }

    @Override
    public QueryState getState()
    {
        return stateMachine.getQueryState();
    }

    @Override
    public Plan getQueryPlan()
    {
        return queryPlan.get();
    }

    private QueryInfo buildQueryInfo(SqlQuerySchedulerInterface scheduler)
    {
        Optional<StageInfo> stageInfo = Optional.empty();
        if (scheduler != null) {
            stageInfo = Optional.of(scheduler.getStageInfo());
        }

        QueryInfo queryInfo = stateMachine.updateQueryInfo(stageInfo);
        if (queryInfo.isFinalQueryInfo()) {
            // capture the final query state and drop reference to the scheduler
            queryScheduler.set(null);
        }

        return queryInfo;
    }

    private static class PlanRoot
    {
        private final SubPlan root;
        private final boolean summarizeTaskInfos;
        private final Set<ConnectorId> connectors;

        public PlanRoot(SubPlan root, boolean summarizeTaskInfos, Set<ConnectorId> connectors)
        {
            this.root = requireNonNull(root, "root is null");
            this.summarizeTaskInfos = summarizeTaskInfos;
            this.connectors = ImmutableSet.copyOf(connectors);
        }

        public SubPlan getRoot()
        {
            return root;
        }

        public boolean isSummarizeTaskInfos()
        {
            return summarizeTaskInfos;
        }

        public Set<ConnectorId> getConnectors()
        {
            return connectors;
        }
    }

    public static class SqlQueryExecutionFactory
            implements QueryExecutionFactory<QueryExecution>
    {
        private final SplitSchedulerStats schedulerStats;
        private final Metadata metadata;
        private final AccessControl accessControl;
        private final SqlParser sqlParser;
        private final SplitManager splitManager;
        private final List<PlanOptimizer> planOptimizers;
        private final List<PlanOptimizer> runtimePlanOptimizers;
        private final PlanFragmenter planFragmenter;
        private final RemoteTaskFactory remoteTaskFactory;
        private final QueryExplainer queryExplainer;
        private final LocationFactory locationFactory;
        private final ExecutorService queryExecutor;
        private final SectionExecutionFactory sectionExecutionFactory;
        private final InternalNodeManager internalNodeManager;
        private final Map<String, ExecutionPolicy> executionPolicies;
        private final StatsCalculator statsCalculator;
        private final CostCalculator costCalculator;
        private final PlanChecker planChecker;
        private final PartialResultQueryManager partialResultQueryManager;

        @Inject
        SqlQueryExecutionFactory(QueryManagerConfig config,
                Metadata metadata,
                AccessControl accessControl,
                SqlParser sqlParser,
                LocationFactory locationFactory,
                SplitManager splitManager,
                PlanOptimizers planOptimizers,
                PlanFragmenter planFragmenter,
                RemoteTaskFactory remoteTaskFactory,
                @ForQueryExecution ExecutorService queryExecutor,
                SectionExecutionFactory sectionExecutionFactory,
                InternalNodeManager internalNodeManager,
                QueryExplainer queryExplainer,
                Map<String, ExecutionPolicy> executionPolicies,
                SplitSchedulerStats schedulerStats,
                StatsCalculator statsCalculator,
                CostCalculator costCalculator,
                PlanChecker planChecker,
                PartialResultQueryManager partialResultQueryManager)
        {
            requireNonNull(config, "config is null");
            this.schedulerStats = requireNonNull(schedulerStats, "schedulerStats is null");
            this.metadata = requireNonNull(metadata, "metadata is null");
            this.accessControl = requireNonNull(accessControl, "accessControl is null");
            this.sqlParser = requireNonNull(sqlParser, "sqlParser is null");
            this.locationFactory = requireNonNull(locationFactory, "locationFactory is null");
            this.splitManager = requireNonNull(splitManager, "splitManager is null");
            requireNonNull(planOptimizers, "planOptimizers is null");
            this.planFragmenter = requireNonNull(planFragmenter, "planFragmenter is null");
            this.remoteTaskFactory = requireNonNull(remoteTaskFactory, "remoteTaskFactory is null");
            this.queryExecutor = requireNonNull(queryExecutor, "queryExecutor is null");
            this.sectionExecutionFactory = requireNonNull(sectionExecutionFactory, "sectionExecutionFactory is null");
            this.internalNodeManager = requireNonNull(internalNodeManager, "internalNodeManager is null");
            this.queryExplainer = requireNonNull(queryExplainer, "queryExplainer is null");
            this.executionPolicies = requireNonNull(executionPolicies, "schedulerPolicies is null");
            this.planOptimizers = planOptimizers.getPlanningTimeOptimizers();
            this.runtimePlanOptimizers = planOptimizers.getRuntimeOptimizers();
            this.statsCalculator = requireNonNull(statsCalculator, "statsCalculator is null");
            this.costCalculator = requireNonNull(costCalculator, "costCalculator is null");
            this.planChecker = requireNonNull(planChecker, "planChecker is null");
            this.partialResultQueryManager = requireNonNull(partialResultQueryManager, "partialResultQueryManager is null");
        }

        @Override
        public QueryExecution createQueryExecution(
                PreparedQuery preparedQuery,
                QueryStateMachine stateMachine,
                String slug,
                int retryCount,
                WarningCollector warningCollector,
                Optional<QueryType> queryType)
        {
            String executionPolicyName = SystemSessionProperties.getExecutionPolicy(stateMachine.getSession());
            ExecutionPolicy executionPolicy = executionPolicies.get(executionPolicyName);
            checkArgument(executionPolicy != null, "No execution policy %s", executionPolicy);

            SqlQueryExecution execution = new SqlQueryExecution(
                    preparedQuery,
                    stateMachine,
                    slug,
                    retryCount,
                    metadata,
                    accessControl,
                    sqlParser,
                    splitManager,
                    planOptimizers,
                    runtimePlanOptimizers,
                    planFragmenter,
                    remoteTaskFactory,
                    locationFactory,
                    queryExecutor,
                    sectionExecutionFactory,
                    internalNodeManager,
                    queryExplainer,
                    executionPolicy,
                    schedulerStats,
                    statsCalculator,
                    costCalculator,
                    warningCollector,
                    planChecker,
                    partialResultQueryManager);

            return execution;
        }
    }
}
