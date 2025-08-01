// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.nereids.trees.copier;

import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.hint.DistributeHint;
import org.apache.doris.nereids.properties.OrderKey;
import org.apache.doris.nereids.trees.expressions.Alias;
import org.apache.doris.nereids.trees.expressions.ExprId;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.MarkJoinSlotReference;
import org.apache.doris.nereids.trees.expressions.NamedExpression;
import org.apache.doris.nereids.trees.expressions.OrderExpression;
import org.apache.doris.nereids.trees.expressions.Slot;
import org.apache.doris.nereids.trees.expressions.SlotReference;
import org.apache.doris.nereids.trees.expressions.StatementScopeIdGenerator;
import org.apache.doris.nereids.trees.expressions.functions.Function;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.logical.LogicalAggregate;
import org.apache.doris.nereids.trees.plans.logical.LogicalApply;
import org.apache.doris.nereids.trees.plans.logical.LogicalAssertNumRows;
import org.apache.doris.nereids.trees.plans.logical.LogicalCTEAnchor;
import org.apache.doris.nereids.trees.plans.logical.LogicalCTEConsumer;
import org.apache.doris.nereids.trees.plans.logical.LogicalCTEProducer;
import org.apache.doris.nereids.trees.plans.logical.LogicalCatalogRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalDeferMaterializeOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalDeferMaterializeTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalEmptyRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalExcept;
import org.apache.doris.nereids.trees.plans.logical.LogicalFilter;
import org.apache.doris.nereids.trees.plans.logical.LogicalGenerate;
import org.apache.doris.nereids.trees.plans.logical.LogicalHaving;
import org.apache.doris.nereids.trees.plans.logical.LogicalIntersect;
import org.apache.doris.nereids.trees.plans.logical.LogicalJoin;
import org.apache.doris.nereids.trees.plans.logical.LogicalLimit;
import org.apache.doris.nereids.trees.plans.logical.LogicalOlapScan;
import org.apache.doris.nereids.trees.plans.logical.LogicalOneRowRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalPartitionTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.nereids.trees.plans.logical.LogicalProject;
import org.apache.doris.nereids.trees.plans.logical.LogicalRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalRepeat;
import org.apache.doris.nereids.trees.plans.logical.LogicalSink;
import org.apache.doris.nereids.trees.plans.logical.LogicalSort;
import org.apache.doris.nereids.trees.plans.logical.LogicalTopN;
import org.apache.doris.nereids.trees.plans.logical.LogicalUnion;
import org.apache.doris.nereids.trees.plans.logical.LogicalWindow;
import org.apache.doris.nereids.trees.plans.visitor.DefaultPlanRewriter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * deep copy a plan
 */
public class LogicalPlanDeepCopier extends DefaultPlanRewriter<DeepCopierContext> {
    public static final Logger LOG = LogManager.getLogger(LogicalPlanDeepCopier.class);

    public static LogicalPlanDeepCopier INSTANCE = new LogicalPlanDeepCopier();

    public LogicalPlan deepCopy(LogicalPlan plan, DeepCopierContext context) {
        return (LogicalPlan) plan.accept(this, context);
    }

    @Override
    public Plan visitLogicalRelation(LogicalRelation logicalRelation, DeepCopierContext context) {
        if (context.getRelationReplaceMap().containsKey(logicalRelation.getRelationId())) {
            return context.getRelationReplaceMap().get(logicalRelation.getRelationId());
        }
        LogicalRelation newRelation =
                logicalRelation.withRelationId(StatementScopeIdGenerator.newRelationId());
        updateReplaceMapWithOutput(logicalRelation, newRelation, context.exprIdReplaceMap);
        context.putRelation(logicalRelation.getRelationId(), newRelation);
        return newRelation;
    }

    @Override
    public Plan visitLogicalCatalogRelation(LogicalCatalogRelation catalogRelation, DeepCopierContext context) {
        if (context.getRelationReplaceMap().containsKey(catalogRelation.getRelationId())) {
            return context.getRelationReplaceMap().get(catalogRelation.getRelationId());
        }
        LogicalCatalogRelation newRelation =
                catalogRelation.withRelationId(StatementScopeIdGenerator.newRelationId());
        updateReplaceMapWithOutput(catalogRelation, newRelation, context.exprIdReplaceMap);
        List<NamedExpression> virtualColumns = catalogRelation.getVirtualColumns().stream()
                .map(e -> {
                    if (e instanceof Alias) {
                        return new Alias(((Alias) e).child(), e.getName());
                    }
                    return e;
                })
                .collect(ImmutableList.toImmutableList());
        for (int i = 0; i < virtualColumns.size(); i++) {
            context.exprIdReplaceMap.put(catalogRelation.getVirtualColumns().get(i).getExprId(),
                    virtualColumns.get(i).getExprId());
        }
        virtualColumns = virtualColumns.stream()
                .map(o -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        newRelation = newRelation.withVirtualColumns(virtualColumns);
        context.putRelation(catalogRelation.getRelationId(), newRelation);
        return updateOperativeSlots(catalogRelation, newRelation);
    }

    @Override
    public Plan visitLogicalEmptyRelation(LogicalEmptyRelation emptyRelation, DeepCopierContext context) {
        if (context.getRelationReplaceMap().containsKey(emptyRelation.getRelationId())) {
            return context.getRelationReplaceMap().get(emptyRelation.getRelationId());
        }
        List<NamedExpression> newProjects = emptyRelation.getProjects().stream()
                .map(p -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(p, context))
                .collect(ImmutableList.toImmutableList());
        LogicalEmptyRelation newEmptyRelation =
                new LogicalEmptyRelation(StatementScopeIdGenerator.newRelationId(), newProjects);
        context.putRelation(emptyRelation.getRelationId(), newEmptyRelation);
        return newEmptyRelation;
    }

    @Override
    public Plan visitLogicalOneRowRelation(LogicalOneRowRelation oneRowRelation, DeepCopierContext context) {
        if (context.getRelationReplaceMap().containsKey(oneRowRelation.getRelationId())) {
            return context.getRelationReplaceMap().get(oneRowRelation.getRelationId());
        }
        List<NamedExpression> newProjects = oneRowRelation.getProjects().stream()
                .map(p -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(p, context))
                .collect(ImmutableList.toImmutableList());
        LogicalOneRowRelation newOneRowRelation =
                new LogicalOneRowRelation(StatementScopeIdGenerator.newRelationId(), newProjects);
        context.putRelation(oneRowRelation.getRelationId(), newOneRowRelation);
        return newOneRowRelation;
    }

    @Override
    public Plan visitLogicalApply(LogicalApply<? extends Plan, ? extends Plan> apply, DeepCopierContext context) {
        Plan left = apply.left().accept(this, context);
        Plan right = apply.right().accept(this, context);
        List<Slot> correlationSlot = apply.getCorrelationSlot().stream()
                .map(s -> (Slot) ExpressionDeepCopier.INSTANCE.deepCopy(s, context))
                .collect(ImmutableList.toImmutableList());
        Optional<Expression> compareExpr = apply.getCompareExpr()
                .map(f -> ExpressionDeepCopier.INSTANCE.deepCopy(f, context));
        Optional<Expression> typeCoercionExpr = apply.getTypeCoercionExpr()
                .map(f -> ExpressionDeepCopier.INSTANCE.deepCopy(f, context));
        Optional<Expression> correlationFilter = apply.getCorrelationFilter()
                .map(f -> ExpressionDeepCopier.INSTANCE.deepCopy(f, context));
        Optional<MarkJoinSlotReference> markJoinSlotReference = apply.getMarkJoinSlotReference()
                .map(m -> (MarkJoinSlotReference) ExpressionDeepCopier.INSTANCE.deepCopy(m, context));
        return new LogicalApply<>(correlationSlot, apply.getSubqueryType(), apply.isNot(),
                compareExpr, typeCoercionExpr, correlationFilter,
                markJoinSlotReference, apply.isNeedAddSubOutputToProjects(),
                apply.isMarkJoinSlotNotNull(), left, right);
    }

    @Override
    public Plan visitLogicalAggregate(LogicalAggregate<? extends Plan> aggregate, DeepCopierContext context) {
        Plan child = aggregate.child().accept(this, context);
        List<Expression> groupByExpressions = aggregate.getGroupByExpressions().stream()
                .map(k -> ExpressionDeepCopier.INSTANCE.deepCopy(k, context))
                .collect(ImmutableList.toImmutableList());
        List<NamedExpression> outputExpressions = aggregate.getOutputExpressions().stream()
                .map(o -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        return aggregate.withChildGroupByAndOutput(groupByExpressions, outputExpressions, child);
    }

    @Override
    public Plan visitLogicalRepeat(LogicalRepeat<? extends Plan> repeat, DeepCopierContext context) {
        Plan child = repeat.child().accept(this, context);
        List<List<Expression>> groupingSets = repeat.getGroupingSets().stream()
                .map(l -> l.stream()
                        .map(e -> ExpressionDeepCopier.INSTANCE.deepCopy(e, context))
                        .collect(ImmutableList.toImmutableList()))
                .collect(ImmutableList.toImmutableList());
        List<NamedExpression> outputExpressions = repeat.getOutputExpressions().stream()
                .map(e -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(e, context))
                .collect(ImmutableList.toImmutableList());
        return new LogicalRepeat<>(groupingSets, outputExpressions, child);
    }

    @Override
    public Plan visitLogicalFilter(LogicalFilter<? extends Plan> filter, DeepCopierContext context) {
        Plan child = filter.child().accept(this, context);
        Set<Expression> conjuncts = filter.getConjuncts().stream()
                .map(p -> ExpressionDeepCopier.INSTANCE.deepCopy(p, context))
                .collect(ImmutableSet.toImmutableSet());
        return new LogicalFilter<>(conjuncts, child);
    }

    @Override
    public Plan visitLogicalDeferMaterializeOlapScan(LogicalDeferMaterializeOlapScan deferMaterializeOlapScan,
            DeepCopierContext context) {
        LogicalOlapScan newScan = (LogicalOlapScan) visitLogicalOlapScan(
                deferMaterializeOlapScan.getLogicalOlapScan(), context);
        Set<ExprId> newSlotIds = deferMaterializeOlapScan.getDeferMaterializeSlotIds().stream()
                .map(context.exprIdReplaceMap::get)
                .collect(ImmutableSet.toImmutableSet());
        SlotReference newRowId = (SlotReference) ExpressionDeepCopier.INSTANCE
                .deepCopy(deferMaterializeOlapScan.getColumnIdSlot(), context);
        return new LogicalDeferMaterializeOlapScan(newScan, newSlotIds, newRowId);
    }

    @Override
    public Plan visitLogicalProject(LogicalProject<? extends Plan> project, DeepCopierContext context) {
        Plan child = project.child().accept(this, context);
        List<NamedExpression> newProjects = project.getProjects().stream()
                .map(p -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(p, context))
                .collect(ImmutableList.toImmutableList());
        return new LogicalProject<>(newProjects, project.isDistinct(), child);
    }

    @Override
    public Plan visitLogicalSort(LogicalSort<? extends Plan> sort, DeepCopierContext context) {
        Plan child = sort.child().accept(this, context);
        List<OrderKey> orderKeys = sort.getOrderKeys().stream()
                .map(o -> new OrderKey(ExpressionDeepCopier.INSTANCE.deepCopy(o.getExpr(), context),
                        o.isAsc(), o.isNullFirst()))
                .collect(ImmutableList.toImmutableList());
        return new LogicalSort<>(orderKeys, child);
    }

    @Override
    public Plan visitLogicalTopN(LogicalTopN<? extends Plan> topN, DeepCopierContext context) {
        Plan child = topN.child().accept(this, context);
        List<OrderKey> orderKeys = topN.getOrderKeys().stream()
                .map(o -> new OrderKey(ExpressionDeepCopier.INSTANCE.deepCopy(o.getExpr(), context),
                        o.isAsc(), o.isNullFirst()))
                .collect(ImmutableList.toImmutableList());
        return new LogicalTopN<>(orderKeys, topN.getLimit(), topN.getOffset(), child);
    }

    @Override
    public Plan visitLogicalDeferMaterializeTopN(LogicalDeferMaterializeTopN<? extends Plan> topN,
            DeepCopierContext context) {
        LogicalTopN<? extends Plan> newTopN
                = (LogicalTopN<? extends Plan>) visitLogicalTopN(topN.getLogicalTopN(), context);
        Set<ExprId> newSlotIds = topN.getDeferMaterializeSlotIds().stream()
                .map(context.exprIdReplaceMap::get)
                .collect(ImmutableSet.toImmutableSet());
        SlotReference newRowId = (SlotReference) ExpressionDeepCopier.INSTANCE
                .deepCopy(topN.getColumnIdSlot(), context);
        return new LogicalDeferMaterializeTopN<>(newTopN, newSlotIds, newRowId);
    }

    @Override
    public Plan visitLogicalPartitionTopN(LogicalPartitionTopN<? extends Plan> partitionTopN,
            DeepCopierContext context) {
        Plan child = partitionTopN.child().accept(this, context);
        List<Expression> partitionKeys = partitionTopN.getPartitionKeys().stream()
                .map(p -> ExpressionDeepCopier.INSTANCE.deepCopy(p, context))
                .collect(ImmutableList.toImmutableList());
        List<OrderExpression> orderKeys = partitionTopN.getOrderKeys().stream()
                .map(o -> (OrderExpression) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        return new LogicalPartitionTopN<>(partitionTopN.getFunction(), partitionKeys, orderKeys,
                partitionTopN.hasGlobalLimit(), partitionTopN.getPartitionLimit(), child);
    }

    @Override
    public Plan visitLogicalLimit(LogicalLimit<? extends Plan> limit, DeepCopierContext context) {
        Plan child = limit.child().accept(this, context);
        return new LogicalLimit<>(limit.getLimit(), limit.getOffset(), limit.getPhase(), child);
    }

    @Override
    public Plan visitLogicalJoin(LogicalJoin<? extends Plan, ? extends Plan> join, DeepCopierContext context) {
        List<Plan> children = join.children().stream()
                .map(c -> c.accept(this, context))
                .collect(ImmutableList.toImmutableList());
        List<Expression> otherJoinConjuncts = join.getOtherJoinConjuncts().stream()
                .map(c -> ExpressionDeepCopier.INSTANCE.deepCopy(c, context))
                .collect(ImmutableList.toImmutableList());
        List<Expression> hashJoinConjuncts = join.getHashJoinConjuncts().stream()
                .map(c -> ExpressionDeepCopier.INSTANCE.deepCopy(c, context))
                .collect(ImmutableList.toImmutableList());
        List<Expression> markJoinConjuncts = join.getMarkJoinConjuncts().stream()
                .map(c -> ExpressionDeepCopier.INSTANCE.deepCopy(c, context))
                .collect(ImmutableList.toImmutableList());
        Optional<MarkJoinSlotReference> markJoinSlotReference = Optional.empty();
        if (join.getMarkJoinSlotReference().isPresent()) {
            markJoinSlotReference = Optional.of((MarkJoinSlotReference) ExpressionDeepCopier.INSTANCE
                    .deepCopy(join.getMarkJoinSlotReference().get(), context));

        }
        DistributeHint hint = join.getDistributeHint();
        if (hint.getSkewInfo() != null) {
            Expression skewExpr = ExpressionDeepCopier.INSTANCE.deepCopy(hint.getSkewExpr(), context);
            hint.setSkewInfo(hint.getSkewInfo().withSkewExpr(skewExpr));
        }
        return new LogicalJoin<>(join.getJoinType(), hashJoinConjuncts, otherJoinConjuncts, markJoinConjuncts,
                hint, markJoinSlotReference, children, join.getJoinReorderContext());
    }

    @Override
    public Plan visitLogicalAssertNumRows(LogicalAssertNumRows<? extends Plan> assertNumRows,
            DeepCopierContext context) {
        Plan child = assertNumRows.child().accept(this, context);
        return new LogicalAssertNumRows<>(assertNumRows.getAssertNumRowsElement(), child);
    }

    @Override
    public Plan visitLogicalHaving(LogicalHaving<? extends Plan> having, DeepCopierContext context) {
        Plan child = having.child().accept(this, context);
        Set<Expression> conjuncts = having.getConjuncts().stream()
                .map(p -> ExpressionDeepCopier.INSTANCE.deepCopy(p, context))
                .collect(ImmutableSet.toImmutableSet());
        return new LogicalHaving<>(conjuncts, child);
    }

    @Override
    public Plan visitLogicalUnion(LogicalUnion union, DeepCopierContext context) {
        List<Plan> children = union.children().stream()
                .map(c -> c.accept(this, context))
                .collect(ImmutableList.toImmutableList());
        List<List<NamedExpression>> constantExprsList = union.getConstantExprsList().stream()
                .map(l -> l.stream()
                        .map(e -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(e, context))
                        .collect(ImmutableList.toImmutableList()))
                .collect(ImmutableList.toImmutableList());
        List<NamedExpression> outputs = union.getOutputs().stream()
                .map(o -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        List<List<SlotReference>> childrenOutputs = union.getRegularChildrenOutputs().stream()
                .map(childOutputs -> childOutputs.stream()
                        .map(o -> (SlotReference) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                        .collect(ImmutableList.toImmutableList()))
                .collect(ImmutableList.toImmutableList());
        return new LogicalUnion(union.getQualifier(), outputs, childrenOutputs,
                constantExprsList, union.hasPushedFilter(), children);
    }

    @Override
    public Plan visitLogicalExcept(LogicalExcept except, DeepCopierContext context) {
        List<Plan> children = except.children().stream()
                .map(c -> c.accept(this, context))
                .collect(ImmutableList.toImmutableList());
        List<NamedExpression> outputs = except.getOutputs().stream()
                .map(o -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        List<List<SlotReference>> childrenOutputs = except.getRegularChildrenOutputs().stream()
                .map(childOutputs -> childOutputs.stream()
                        .map(o -> (SlotReference) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                        .collect(ImmutableList.toImmutableList()))
                .collect(ImmutableList.toImmutableList());
        return new LogicalExcept(except.getQualifier(), outputs, childrenOutputs, children);
    }

    @Override
    public Plan visitLogicalIntersect(LogicalIntersect intersect, DeepCopierContext context) {
        List<Plan> children = intersect.children().stream()
                .map(c -> c.accept(this, context))
                .collect(ImmutableList.toImmutableList());
        List<NamedExpression> outputs = intersect.getOutputs().stream()
                .map(o -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        List<List<SlotReference>> childrenOutputs = intersect.getRegularChildrenOutputs().stream()
                .map(childOutputs -> childOutputs.stream()
                        .map(o -> (SlotReference) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                        .collect(ImmutableList.toImmutableList()))
                .collect(ImmutableList.toImmutableList());
        return new LogicalIntersect(intersect.getQualifier(), outputs, childrenOutputs, children);
    }

    @Override
    public Plan visitLogicalGenerate(LogicalGenerate<? extends Plan> generate, DeepCopierContext context) {
        Plan child = generate.child().accept(this, context);
        List<Function> generators = generate.getGenerators().stream()
                .map(g -> (Function) ExpressionDeepCopier.INSTANCE.deepCopy(g, context))
                .collect(ImmutableList.toImmutableList());
        List<Slot> generatorOutput = generate.getGeneratorOutput().stream()
                .map(o -> (Slot) ExpressionDeepCopier.INSTANCE.deepCopy(o, context))
                .collect(ImmutableList.toImmutableList());
        return new LogicalGenerate<>(generators, generatorOutput, generate.getExpandColumnAlias(), child);
    }

    @Override
    public Plan visitLogicalWindow(LogicalWindow<? extends Plan> window, DeepCopierContext context) {
        Plan child = window.child().accept(this, context);
        List<NamedExpression> windowExpressions = window.getWindowExpressions().stream()
                .map(w -> (NamedExpression) ExpressionDeepCopier.INSTANCE.deepCopy(w, context))
                .collect(ImmutableList.toImmutableList());
        return new LogicalWindow<>(windowExpressions, window.isChecked(), child);
    }

    @Override
    public Plan visitLogicalSink(LogicalSink<? extends Plan> logicalSink, DeepCopierContext context) {
        Plan child = logicalSink.child().accept(this, context);
        return logicalSink.withChildren(child);
    }

    @Override
    public Plan visitLogicalCTEProducer(LogicalCTEProducer<? extends Plan> cteProducer, DeepCopierContext context) {
        throw new AnalysisException("plan deep copier could not copy CTEProducer.");
    }

    @Override
    public Plan visitLogicalCTEConsumer(LogicalCTEConsumer cteConsumer, DeepCopierContext context) {
        if (context.getRelationReplaceMap().containsKey(cteConsumer.getRelationId())) {
            return context.getRelationReplaceMap().get(cteConsumer.getRelationId());
        }
        Map<Slot, Slot> consumerToProducerOutputMap = new LinkedHashMap<>();
        Multimap<Slot, Slot> producerToConsumerOutputMap = LinkedHashMultimap.create();
        for (Slot consumerOutput : cteConsumer.getOutput()) {
            Slot newOutput = (Slot) ExpressionDeepCopier.INSTANCE.deepCopy(consumerOutput, context);
            consumerToProducerOutputMap.put(newOutput, cteConsumer.getProducerSlot(consumerOutput));
            producerToConsumerOutputMap.put(cteConsumer.getProducerSlot(consumerOutput), newOutput);
        }
        LogicalCTEConsumer newCTEConsumer = new LogicalCTEConsumer(
                StatementScopeIdGenerator.newRelationId(),
                cteConsumer.getCteId(), cteConsumer.getName(),
                consumerToProducerOutputMap, producerToConsumerOutputMap);
        context.putRelation(cteConsumer.getRelationId(), newCTEConsumer);
        return newCTEConsumer;
    }

    @Override
    public Plan visitLogicalCTEAnchor(LogicalCTEAnchor<? extends Plan, ? extends Plan> cteAnchor,
            DeepCopierContext context) {
        throw new AnalysisException("plan deep copier could not copy CTEAnchor.");
    }

    private void updateReplaceMapWithOutput(Plan oldPlan, Plan newPlan, Map<ExprId, ExprId> replaceMap) {
        List<Slot> oldOutput = oldPlan.getOutput();
        List<Slot> newOutput = newPlan.getOutput();
        for (int i = 0; i < newOutput.size(); i++) {
            replaceMap.put(oldOutput.get(i).getExprId(), newOutput.get(i).getExprId());
        }
    }

    private Plan updateOperativeSlots(LogicalCatalogRelation oldRelation, LogicalCatalogRelation newRelation) {
        List<Slot> oldOperativeSlots = oldRelation.getOperativeSlots();
        List<Slot> newOperativeSlots = new ArrayList<>(oldOperativeSlots.size());
        int outputSize = oldOperativeSlots.size();
        for (Slot opSlot : oldOperativeSlots) {
            int idx;
            for (idx = 0; idx < outputSize; idx++) {
                if (opSlot.equals(oldRelation.getOutput().get(idx))) {
                    newOperativeSlots.add(newRelation.getOutput().get(idx));
                    break;
                }
            }
            if (idx == outputSize) {
                LOG.warn("deep copy failed, cannot find operative slot {} from {}",
                        opSlot, oldRelation.treeString());
            }
        }
        return (Plan) newRelation.withOperativeSlots(newOperativeSlots);
    }

}
