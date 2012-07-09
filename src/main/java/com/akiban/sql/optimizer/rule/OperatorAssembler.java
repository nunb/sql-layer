/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.sql.optimizer.rule;

import static com.akiban.sql.optimizer.rule.OldExpressionAssembler.*;

import com.akiban.qp.operator.API.JoinType;
import com.akiban.server.expression.std.FieldExpression;
import com.akiban.server.expression.subquery.ResultSetSubqueryExpression;
import com.akiban.server.expression.subquery.ScalarSubqueryExpression;
import com.akiban.server.types3.TAggregator;
import com.akiban.server.types3.TInstance;
import com.akiban.server.types3.Types3Switch;
import com.akiban.server.types3.pvalue.PUnderlying;
import com.akiban.server.types3.pvalue.PValueSources;
import com.akiban.server.types3.texpressions.AnySubqueryTExpression;
import com.akiban.server.types3.texpressions.TPreparedExpression;
import com.akiban.server.types3.texpressions.TPreparedField;
import com.akiban.server.types3.texpressions.TPreparedLiteral;
import com.akiban.sql.optimizer.*;
import com.akiban.sql.optimizer.plan.*;
import com.akiban.sql.optimizer.plan.ExpressionsSource.DistinctState;
import com.akiban.sql.optimizer.plan.PhysicalSelect.PhysicalResultColumn;
import com.akiban.sql.optimizer.plan.ResultSet.ResultField;
import com.akiban.sql.optimizer.plan.Sort.OrderByExpression;
import com.akiban.sql.optimizer.plan.UpdateStatement.UpdateColumn;

import com.akiban.sql.optimizer.rule.range.ColumnRanges;
import com.akiban.sql.optimizer.rule.range.RangeSegment;
import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.parser.ParameterNode;

import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.error.UnsupportedSQLException;

import com.akiban.server.expression.Expression;
import com.akiban.server.expression.std.Expressions;
import com.akiban.server.expression.std.LiteralExpression;
import com.akiban.server.expression.subquery.AnySubqueryExpression;
import com.akiban.server.expression.subquery.ExistsSubqueryExpression;
import com.akiban.server.types.AkType;

import com.akiban.qp.exec.UpdatePlannable;
import com.akiban.qp.operator.API;
import com.akiban.qp.operator.IndexScanSelector;
import com.akiban.qp.operator.Operator;
import com.akiban.qp.operator.UpdateFunction;
import com.akiban.qp.row.BindableRow;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.*;

import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.expression.IndexKeyRange;
import com.akiban.qp.expression.RowBasedUnboundExpressions;
import com.akiban.qp.expression.UnboundExpressions;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.GroupTable;

import com.akiban.server.api.dml.ColumnSelector;
import com.akiban.util.tap.PointTap;
import com.akiban.util.tap.Tap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class OperatorAssembler extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(OperatorAssembler.class);
    private static final PointTap SELECT_COUNT = Tap.createCount("sql: select");
    private static final PointTap INSERT_COUNT = Tap.createCount("sql: insert");
    private static final PointTap UPDATE_COUNT = Tap.createCount("sql: update");
    private static final PointTap DELETE_COUNT = Tap.createCount("sql: delete");

    public static final int INSERTION_SORT_MAX_LIMIT = 100;

    private final boolean usePValues;

    @SuppressWarnings("unused") // used by reflection in RulesTestHelper
    public OperatorAssembler() {
        this.usePValues = Types3Switch.ON;
    }
    
    public OperatorAssembler(boolean usePValues) {
        this.usePValues = usePValues;
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext plan) {
        new Assembler(plan, usePValues).apply();
    }

    interface PartialAssembler<T> extends SubqueryOperatorAssembler<T> {
        List<T> assembleExpressions(List<ExpressionNode> expressions,
                                    ColumnExpressionToIndex fieldOffsets);
        List<T> assembleExpressionsA(List<? extends AnnotatedExpression> expressions,
                                     ColumnExpressionToIndex fieldOffsets);
        T assembleExpression(ExpressionNode expr, ColumnExpressionToIndex fieldOffsets);
        Operator assembleAggregates(Operator inputOperator, RowType inputRowType, int inputsIndex, List<String> names);
    }

    private static final PartialAssembler<?> NULL_PARTIAL_ASSEMBLER = new PartialAssembler<Object>() {
        @Override
        public List<Object> assembleExpressions(List<ExpressionNode> expressions,
                                                ColumnExpressionToIndex fieldOffsets) {
            return null;
        }

        @Override
        public List<Object> assembleExpressionsA(List<? extends AnnotatedExpression> expressions,
                                                 ColumnExpressionToIndex fieldOffsets) {
            return null;
        }

        @Override
        public Object assembleExpression(ExpressionNode expr, ColumnExpressionToIndex fieldOffsets) {
            return null;
        }

        @Override
        public Object assembleSubqueryExpression(SubqueryExpression subqueryExpression) {
            return null;
        }

        @Override
        public Operator assembleAggregates(Operator inputOperator, RowType inputRowType, int inputsIndex,
                                           List<String> names) {
            throw new AssertionError();
        }
    };

    @SuppressWarnings("unchecked")
    private static <T> PartialAssembler<T> nullAssembler() {
        return (PartialAssembler<T>) NULL_PARTIAL_ASSEMBLER;
    }

    static class Assembler {

        abstract class BasePartialAssembler<T> implements PartialAssembler<T> {

            protected BasePartialAssembler(ExpressionAssembler<T> expressionAssembler) {
                this.expressionAssembler = expressionAssembler;
            }

            private ExpressionAssembler<T> expressionAssembler;

            // Assemble a list of expressions from the given nodes.
            @Override
            public List<T> assembleExpressions(List<ExpressionNode> expressions,
                                                           ColumnExpressionToIndex fieldOffsets) {
                List<T> result = new ArrayList<T>(expressions.size());
                for (ExpressionNode expr : expressions) {
                    result.add(assembleExpression(expr, fieldOffsets));
                }
                return result;
            }

            // Assemble a list of expressions from the given nodes.
            @Override
            public List<T> assembleExpressionsA(List<? extends AnnotatedExpression> expressions,
                                                            ColumnExpressionToIndex fieldOffsets) {
                List<T> result = new ArrayList<T>(expressions.size());
                for (AnnotatedExpression aexpr : expressions) {
                    result.add(assembleExpression(aexpr.getExpression(), fieldOffsets));
                }
                return result;
            }

            // Assemble an expression against the given row offsets.
            @Override
            public T assembleExpression(ExpressionNode expr, ColumnExpressionToIndex fieldOffsets) {
                ColumnExpressionContext context = getColumnExpressionContext(fieldOffsets);
                return expressionAssembler.assembleExpression(expr, context, this);
            }

            // Assemble an aggregate operator
            @Override
            public Operator assembleAggregates(Operator inputOperator, RowType inputRowType, int inputsIndex,
                                               List<String> names) {
                return expressionAssembler.assembleAggregates(inputOperator, inputRowType, inputsIndex, names);
            }

            protected abstract T existsExpression(Operator operator, RowType outerRowType,
                                                  RowType innerRowType,
                                                  int bindingPosition);
            protected abstract T anyExpression(Operator operator, T innerExpression, RowType outerRowType,
                                               RowType innerRowType,
                                               int bindingPosition);
            protected abstract T scalarSubqueryExpression(Operator operator, T innerExpression,
                                                          RowType outerRowType,
                                                          RowType innerRowType,
                                                          int bindingPosition);
            protected abstract T resultSetSubqueryExpression(Operator operator, RowType outerRowType,
                                                             RowType innerRowType,
                                                             int bindingPosition);
            protected abstract T field(RowType rowType, int position);

            @Override
            public T assembleSubqueryExpression(SubqueryExpression sexpr) {
                ColumnExpressionToIndex fieldOffsets = columnBoundRows.current;
                RowType outerRowType = null;
                if (fieldOffsets != null)
                    outerRowType = fieldOffsets.getRowType();
                pushBoundRow(fieldOffsets);
                PlanNode subquery = sexpr.getSubquery().getQuery();
                ExpressionNode expression = null;
                boolean fieldExpression = false;
                if ((sexpr instanceof AnyCondition) ||
                        (sexpr instanceof SubqueryValueExpression)) {
                    if (subquery instanceof ResultSet)
                        subquery = ((ResultSet)subquery).getInput();
                    if (subquery instanceof Project) {
                        Project project = (Project)subquery;
                        subquery = project.getInput();
                        expression = project.getFields().get(0);
                    }
                    else {
                        fieldExpression = true;
                    }
                }
                RowStream stream = assembleQuery(subquery);
                T innerExpression = null;
                if (fieldExpression)
                    innerExpression = field(stream.rowType, 0);
                else if (expression != null)
                    innerExpression = assembleExpression(expression, stream.fieldOffsets);
                T result = assembleSubqueryExpression(sexpr,
                        stream.operator,
                        innerExpression,
                        outerRowType,
                        stream.rowType,
                        currentBindingPosition());
                popBoundRow();
                columnBoundRows.current = fieldOffsets;
                return result;
            }

            private T assembleSubqueryExpression(SubqueryExpression sexpr,
                                                 Operator operator,
                                                 T innerExpression,
                                                 RowType outerRowType,
                                                 RowType innerRowType,
                                                 int bindingPosition) {
                if (sexpr instanceof ExistsCondition)
                    return existsExpression(operator, outerRowType,
                            innerRowType, bindingPosition);
                else if (sexpr instanceof AnyCondition)
                    return anyExpression(operator, innerExpression,
                            outerRowType, innerRowType, bindingPosition);
                else if (sexpr instanceof SubqueryValueExpression)
                    return scalarSubqueryExpression(operator, innerExpression,
                                                    outerRowType, innerRowType,
                                                    bindingPosition);
                else if (sexpr instanceof SubqueryResultSetExpression)
                    return resultSetSubqueryExpression(operator, outerRowType,
                                                       innerRowType, bindingPosition);
                else
                    throw new UnsupportedSQLException("Unknown subquery", sexpr.getSQLsource());
            }
        }

        private class OldPartialAssembler extends BasePartialAssembler<Expression> {

            private OldPartialAssembler(RulesContext context) {
                super(new OldExpressionAssembler(context));
            }

            @Override
            protected Expression existsExpression(Operator operator, RowType outerRowType, RowType innerRowType,
                                                  int bindingPosition) {
                return new ExistsSubqueryExpression(operator, outerRowType, innerRowType, bindingPosition);
            }

            @Override
            protected Expression anyExpression(Operator operator, Expression innerExpression, RowType outerRowType,
                                               RowType innerRowType,
                                               int bindingPosition) {
                return new AnySubqueryExpression(operator, innerExpression, outerRowType, innerRowType,
                        bindingPosition);
            }

            @Override
            protected Expression scalarSubqueryExpression(Operator operator, Expression innerExpression,
                                                          RowType outerRowType, RowType innerRowType,
                                                          int bindingPosition) {
                return new ScalarSubqueryExpression(operator, innerExpression, outerRowType, innerRowType,
                        bindingPosition);
            }

            @Override
            protected Expression resultSetSubqueryExpression(Operator operator, RowType outerRowType,
                                                             RowType innerRowType,
                                                             int bindingPosition) {
                return new ResultSetSubqueryExpression(operator, outerRowType, innerRowType, bindingPosition);
            }

            @Override
            protected Expression field(RowType rowType, int position) {
                return new FieldExpression(rowType, position);
            }
        }

        private class NewPartialAssembler extends BasePartialAssembler<TPreparedExpression> {
            private NewPartialAssembler(RulesContext context) {
                super(new NewExpressionAssembler(context));
            }

            @Override
            protected TPreparedExpression existsExpression(Operator operator, RowType outerRowType,
                                                           RowType innerRowType,
                                                           int bindingPosition) {
                throw new UnsupportedOperationException(); // TODO
            }

            @Override
            protected TPreparedExpression anyExpression(Operator operator, TPreparedExpression innerExpression,
                                                        RowType outerRowType,
                                                        RowType innerRowType, int bindingPosition) {
                return new AnySubqueryTExpression(operator, innerExpression, outerRowType, innerRowType, bindingPosition);
            }

            @Override
            protected TPreparedExpression scalarSubqueryExpression(Operator operator, TPreparedExpression innerExpression,
                                                                   RowType outerRowType, RowType innerRowType,
                                                                   int bindingPosition) {
                throw new UnsupportedOperationException(); // TODO
            }

            @Override
            protected TPreparedExpression resultSetSubqueryExpression(Operator operator, RowType outerRowType,
                                                                      RowType innerRowType, int bindingPosition) {
                throw new UnsupportedOperationException(); // TODO
            }

            @Override
            protected TPreparedExpression field(RowType rowType, int position) {
                return new TPreparedField(rowType.typeInstanceAt(position), position);
            }
        }

        private PlanContext planContext;
        private SchemaRulesContext rulesContext;
        private Schema schema;
        private boolean usePValues;
        private final PartialAssembler<Expression> oldPartialAssembler;
        private final PartialAssembler<TPreparedExpression> newPartialAssembler;

        public Assembler(PlanContext planContext, boolean usePValues) {
            this.usePValues = usePValues;
            this.planContext = planContext;
            rulesContext = (SchemaRulesContext)planContext.getRulesContext();
            schema = rulesContext.getSchema();
            if (usePValues) {
                newPartialAssembler = new NewPartialAssembler(rulesContext);
                oldPartialAssembler = nullAssembler();
            }
            else {
                newPartialAssembler = nullAssembler();
                oldPartialAssembler = new OldPartialAssembler(rulesContext);
            }
            computeBindingsOffsets();
        }

        public void apply() {
            planContext.setPlan(assembleStatement((BaseStatement)planContext.getPlan()));
        }
        
        protected BasePlannable assembleStatement(BaseStatement plan) {
            if (plan instanceof SelectQuery) {
                SELECT_COUNT.hit();
                return selectQuery((SelectQuery)plan);
            } else if (plan instanceof InsertStatement) {
                INSERT_COUNT.hit();
                return insertStatement((InsertStatement)plan);
            } else if (plan instanceof UpdateStatement) {
                UPDATE_COUNT.hit();
                return updateStatement((UpdateStatement)plan);
            } else if (plan instanceof DeleteStatement) {
                DELETE_COUNT.hit();
                return deleteStatement((DeleteStatement)plan);
            } else
                throw new UnsupportedSQLException("Cannot assemble plan: " + plan, null);
        }

        protected PhysicalSelect selectQuery(SelectQuery selectQuery) {
            PlanNode planQuery = selectQuery.getQuery();
            RowStream stream = assembleQuery(planQuery);
            List<PhysicalResultColumn> resultColumns;
            if (planQuery instanceof ResultSet) {
                List<ResultField> results = ((ResultSet)planQuery).getFields();
                resultColumns = getResultColumns(results);
            }
            else {
                // VALUES results in column1, column2, ...
                resultColumns = getResultColumns(stream.rowType.nFields());
            }
            return new PhysicalSelect(stream.operator, stream.rowType, resultColumns, 
                                      getParameterTypes());
        }

        protected PhysicalUpdate insertStatement(InsertStatement insertStatement) {
            PlanNode planQuery = insertStatement.getQuery();
            List<ExpressionNode> projectFields = null;
            if (planQuery instanceof Project) {
                Project project = (Project)planQuery;
                projectFields = project.getFields();
                planQuery = project.getInput();
            }
            RowStream stream = assembleQuery(planQuery);
            UserTableRowType targetRowType = 
                tableRowType(insertStatement.getTargetTable());
            List<Expression> inserts = null;
            List<TPreparedExpression> insertsP = null;
            if (projectFields != null) {
                // In the common case, we can project into a wider row
                // of the correct type directly.
                inserts = usePValues
                        ? null
                        : oldPartialAssembler.assembleExpressions(projectFields, stream.fieldOffsets);
            }
            else {
                // VALUES just needs each field, which will get rearranged below.
                int nfields = stream.rowType.nFields();
                if (usePValues) {
                    insertsP = new ArrayList<TPreparedExpression>(nfields);
                    for (int i = 0; i < nfields; ++i) {
                        insertsP.add(new TPreparedField(stream.rowType.typeInstanceAt(i), i));
                    }
                }
                else {
                    inserts = new ArrayList<Expression>(nfields);
                    for (int i = 0; i < nfields; i++) {
                        inserts.add(Expressions.field(stream.rowType, i));
                    }
                }
            }
            // Have a list of expressions in the order specified.
            // Want a list as wide as the target row with NULL
            // literals for the gaps.
            // TODO: That doesn't seem right. How are explicit NULLs
            // to be distinguished from the column's default value?
            if (inserts != null) {
                Expression[] row = new Expression[targetRowType.nFields()];
                Arrays.fill(row, LiteralExpression.forNull());
                int ncols = inserts.size();
                for (int i = 0; i < ncols; i++) {
                    Column column = insertStatement.getTargetColumns().get(i);
                    row[column.getPosition()] = inserts.get(i);
                }
                inserts = Arrays.asList(row);
            }
            else {
                TPreparedExpression[] row = new TPreparedExpression[targetRowType.nFields()];
                int ncols = insertsP.size();
                for (int i = 0; i < ncols; i++) {
                    Column column = insertStatement.getTargetColumns().get(i);
                    row[column.getPosition()] = insertsP.get(i);
                }
                for (int i = 0, len = targetRowType.nFields(); i < len; ++i) {
                    if (row[i] == null) {
                        TInstance tinst = targetRowType.typeInstanceAt(i);
                        PUnderlying underlying = tinst.typeClass().underlyingType();
                        row[i] = new TPreparedLiteral(tinst, PValueSources.getNullSource(underlying));
                    }
                }
            }
            stream.operator = API.project_Table(stream.operator, stream.rowType,
                                                targetRowType, inserts, null);
            UpdatePlannable plan = API.insert_Default(stream.operator);
            return new PhysicalUpdate(plan, getParameterTypes());
        }

        protected PhysicalUpdate updateStatement(UpdateStatement updateStatement) {
            RowStream stream = assembleQuery(updateStatement.getQuery());
            UserTableRowType targetRowType = 
                tableRowType(updateStatement.getTargetTable());
            assert (stream.rowType == targetRowType);
            List<UpdateColumn> updateColumns = updateStatement.getUpdateColumns();
            List<Expression> updates = oldPartialAssembler.assembleExpressionsA(updateColumns,
                    stream.fieldOffsets);
            // Have a list of expressions in the order specified.
            // Want a list as wide as the target row with Java nulls
            // for the gaps.
            // TODO: It might be simpler to have an update function
            // that knew about column offsets for ordered expressions.
            Expression[] row = new Expression[targetRowType.nFields()];
            for (int i = 0; i < updateColumns.size(); i++) {
                UpdateColumn column = updateColumns.get(i);
                row[column.getColumn().getPosition()] = updates.get(i);
            }
            updates = Arrays.asList(row);
            UpdateFunction updateFunction = 
                new ExpressionRowUpdateFunction(updates, targetRowType);
            UpdatePlannable plan = API.update_Default(stream.operator, updateFunction);
            return new PhysicalUpdate(plan, getParameterTypes());
        }

        protected PhysicalUpdate deleteStatement(DeleteStatement deleteStatement) {
            RowStream stream = assembleQuery(deleteStatement.getQuery());
            assert (stream.rowType == tableRowType(deleteStatement.getTargetTable()));
            UpdatePlannable plan = API.delete_Default(stream.operator);
            return new PhysicalUpdate(plan, getParameterTypes());
        }

        // Assemble the top-level query. If there is a ResultSet at
        // the top, it is not handled here, since its meaning is
        // different for the different statement types.
        protected RowStream assembleQuery(PlanNode planQuery) {
            if (planQuery instanceof ResultSet)
                planQuery = ((ResultSet)planQuery).getInput();
            return assembleStream(planQuery);
        }

        // Assemble an ordinary stream node.
        protected RowStream assembleStream(PlanNode node) {
            if (node instanceof IndexScan)
                return assembleIndexScan((IndexScan) node);
            else if (node instanceof GroupScan)
                return assembleGroupScan((GroupScan) node);
            else if (node instanceof Select)
                return assembleSelect((Select) node);
            else if (node instanceof Flatten)
                return assembleFlatten((Flatten) node);
            else if (node instanceof AncestorLookup)
                return assembleAncestorLookup((AncestorLookup) node);
            else if (node instanceof BranchLookup)
                return assembleBranchLookup((BranchLookup) node);
            else if (node instanceof MapJoin)
                return assembleMapJoin((MapJoin) node);
            else if (node instanceof Product)
                return assembleProduct((Product) node);
            else if (node instanceof AggregateSource)
                return assembleAggregateSource((AggregateSource) node);
            else if (node instanceof Distinct)
                return assembleDistinct((Distinct) node);
            else if (node instanceof Sort            )
                return assembleSort((Sort) node);
            else if (node instanceof Limit)
                return assembleLimit((Limit) node);
            else if (node instanceof NullIfEmpty)
                return assembleNullIfEmpty((NullIfEmpty) node);
            else if (node instanceof OnlyIfEmpty)
                return assembleOnlyIfEmpty((OnlyIfEmpty) node);
            else if (node instanceof Project)
                return assembleProject((Project) node);
            else if (node instanceof ExpressionsSource)
                return assembleExpressionsSource((ExpressionsSource) node);
            else if (node instanceof SubquerySource)
                return assembleSubquerySource((SubquerySource) node);
            else if (node instanceof NullSource)
                return assembleNullSource((NullSource) node);
            else if (node instanceof UsingBloomFilter)
                return assembleUsingBloomFilter((UsingBloomFilter) node);
            else if (node instanceof BloomFilterFilter)
                return assembleBloomFilterFilter((BloomFilterFilter) node);
            else
                throw new UnsupportedSQLException("Plan node " + node, null);
        }
        
        protected RowStream assembleIndexScan(IndexScan index) {
            return assembleIndexScan(index, false, useSkipScan(index));
        }

        protected RowStream assembleIndexScan(IndexScan index, boolean forIntersection, boolean useSkipScan) {
            if (index instanceof SingleIndexScan)
                return assembleSingleIndexScan((SingleIndexScan) index, forIntersection);
            else if (index instanceof MultiIndexIntersectScan)
                return assembleIndexIntersection((MultiIndexIntersectScan) index, useSkipScan);
            else
                throw new UnsupportedSQLException("Plan node " + index, null);
        }

        private RowStream assembleIndexIntersection(MultiIndexIntersectScan index, boolean useSkipScan) {
            RowStream stream = new RowStream();
            RowStream outputScan = assembleIndexScan(index.getOutputIndexScan(), 
                                                     true, useSkipScan);
            RowStream selectorScan = assembleIndexScan(index.getSelectorIndexScan(), 
                                                       true, useSkipScan);
            stream.operator = API.intersect_Ordered(
                    outputScan.operator,
                    selectorScan.operator,
                    (IndexRowType) outputScan.rowType,
                    (IndexRowType) selectorScan.rowType,
                    index.getOutputOrderingFields(),
                    index.getSelectorOrderingFields(),
                    index.getComparisonFieldDirections(),
                    JoinType.INNER_JOIN,
                    (useSkipScan) ? 
                    EnumSet.of(API.IntersectOption.OUTPUT_LEFT, 
                               API.IntersectOption.SKIP_SCAN) :
                    EnumSet.of(API.IntersectOption.OUTPUT_LEFT, 
                               API.IntersectOption.SEQUENTIAL_SCAN),
                    usePValues);
            stream.rowType = outputScan.rowType;
            stream.fieldOffsets = new IndexFieldOffsets(index, stream.rowType);
            return stream;
        }

        protected RowStream assembleSingleIndexScan(SingleIndexScan indexScan, boolean forIntersection) {
            RowStream stream = new RowStream();
            Index index = indexScan.getIndex();
            IndexRowType indexRowType = schema.indexRowType(index);
            IndexScanSelector selector;
            if (index.isTableIndex()) {
                selector = IndexScanSelector.inner(index);
            }
            else {
                switch (index.getJoinType()) {
                case LEFT:
                    selector = IndexScanSelector
                        .leftJoinAfter(index, 
                                       indexScan.getLeafMostInnerTable().getTable().getTable());
                    break;
                case RIGHT:
                    selector = IndexScanSelector
                        .rightJoinUntil(index, 
                                        indexScan.getRootMostInnerTable().getTable().getTable());
                    break;
                default:
                    throw new AkibanInternalException("Unknown index join type " +
                                                      index);
                }
            }
            if (indexScan.getConditionRange() == null) {
                stream.operator = API.indexScan_Default(indexRowType,
                                                        assembleIndexKeyRange(indexScan, null),
                                                        assembleIndexOrdering(indexScan, indexRowType),
                                                        selector);
                stream.rowType = indexRowType;
            }
            else {
                ColumnRanges range = indexScan.getConditionRange();
                // Non-single-point ranges are ordered by the ranges
                // themselves as part of merging segments, so that index
                // column is an ordering column.
                // Single-point ranges have only one value for the range column,
                // so they can order by the following columns if we're
                // willing to do the more expensive ordered union.
                // Determine whether anything is taking advantage of this:
                // * Index is being intersected.
                // * Index is effective for query ordering.
                // ** See also special case in AggregateSplitter.directIndexMinMax().
                boolean unionOrdered = (range.isAllSingle() && 
                     (forIntersection || (indexScan.getOrderEffectiveness() != IndexScan.OrderEffectiveness.NONE)));
                for (RangeSegment rangeSegment : range.getSegments()) {
                    Operator scan = API.indexScan_Default(indexRowType,
                                                          assembleIndexKeyRange(indexScan, null, rangeSegment),
                                                          assembleIndexOrdering(indexScan, indexRowType),
                                                          selector);
                    if (stream.operator == null) {
                        stream.operator = scan;
                        stream.rowType = indexRowType;
                    }
                    else if (unionOrdered) {
                        int nequals = indexScan.getNEquality();
                        List<OrderByExpression> ordering = indexScan.getOrdering();
                        int nordering = ordering.size() - nequals;
                        boolean[] ascending = new boolean[nordering];
                        for (int i = 0; i < nordering; i++) {
                            ascending[i] = ordering.get(nequals + i).isAscending();
                        }
                        stream.operator = API.union_Ordered(stream.operator, scan,
                                                            (IndexRowType)stream.rowType, indexRowType,
                                                            nordering, nordering, 
                                                            ascending,
                                                            usePValues);
                    }
                    else {
                        stream.operator = API.unionAll(stream.operator, stream.rowType, scan, indexRowType);
                        stream.rowType = stream.operator.rowType();
                    }
                }
            }
            stream.fieldOffsets = new IndexFieldOffsets(indexScan, indexRowType);
            return stream;
        }

        /** If there are this many or more scans feeding into a tree
         * of intersection / union, then skip scan is enabled for it.
         * (3 scans means 2 intersections or intersection with a two-value union.)
         */
        public static final int SKIP_SCAN_MIN_COUNT_DEFAULT = 3;

        protected boolean useSkipScan(IndexScan index) {
            if (!(index instanceof MultiIndexIntersectScan))
                return false;
            int count = countScans(index);
            int minCount;
            String prop = rulesContext.getProperty("skipScanMinCount");
            if (prop != null)
                minCount = Integer.valueOf(prop);
            else
                minCount = SKIP_SCAN_MIN_COUNT_DEFAULT;
            return (count >= minCount);
        }

        private int countScans(IndexScan index) {
            if (index instanceof SingleIndexScan) {
                SingleIndexScan sindex = (SingleIndexScan)index;
                if (sindex.getConditionRange() == null)
                    return 1;
                else
                    return sindex.getConditionRange().getSegments().size();
            }
            else if (index instanceof MultiIndexIntersectScan) {
                MultiIndexIntersectScan mindex = (MultiIndexIntersectScan)index;
                return countScans(mindex.getOutputIndexScan()) +
                       countScans(mindex.getSelectorIndexScan());
            }
            else
                return 0;
        }

        protected RowStream assembleGroupScan(GroupScan groupScan) {
            RowStream stream = new RowStream();
            GroupTable groupTable = groupScan.getGroup().getGroup().getGroupTable();
            stream.operator = API.groupScan_Default(groupTable);
            stream.unknownTypesPresent = true;
            return stream;
        }

        protected RowStream assembleExpressionsSource(ExpressionsSource expressionsSource) {
            RowStream stream = new RowStream();
            stream.rowType = valuesRowType(expressionsSource.getFieldTypes());
            List<BindableRow> bindableRows = new ArrayList<BindableRow>();
            for (List<ExpressionNode> exprs : expressionsSource.getExpressions()) {
                List<Expression> expressions = oldPartialAssembler.assembleExpressions(exprs, stream.fieldOffsets);
                bindableRows.add(BindableRow.of(stream.rowType, expressions));
            }
            stream.operator = API.valuesScan_Default(bindableRows, stream.rowType);
            stream.fieldOffsets = new ColumnSourceFieldOffsets(expressionsSource, 
                                                               stream.rowType);
            if (expressionsSource.getDistinctState() == DistinctState.NEED_DISTINCT) {
                // Add Sort (usually _InsertionLimited) and Distinct.
                assembleSort(stream, stream.rowType.nFields(), expressionsSource,
                             API.SortOption.SUPPRESS_DUPLICATES);
            }
            return stream;
        }

        protected RowStream assembleSubquerySource(SubquerySource subquerySource) {
            PlanNode subquery = subquerySource.getSubquery().getQuery();
            if (subquery instanceof ResultSet)
                subquery = ((ResultSet)subquery).getInput();
            RowStream stream = assembleStream(subquery);
            stream.fieldOffsets = new ColumnSourceFieldOffsets(subquerySource, 
                                                               stream.rowType);
            return stream;
        }

        protected RowStream assembleNullSource(NullSource node) {
            return assembleExpressionsSource(new ExpressionsSource(Collections.<List<ExpressionNode>>emptyList()));
        }

        protected RowStream assembleSelect(Select select) {
            RowStream stream = assembleStream(select.getInput());
            ConditionDependencyAnalyzer dependencies = null;
            for (ConditionExpression condition : select.getConditions()) {
                RowType rowType = stream.rowType;
                ColumnExpressionToIndex fieldOffsets = stream.fieldOffsets;
                if (rowType == null) {
                    // Pre-flattening case: get the single table this
                    // condition must have come from and use its row-type.
                    // TODO: Would it be better if earlier rule saved this?
                    if (dependencies == null)
                        dependencies = new ConditionDependencyAnalyzer(select);
                    TableSource table = (TableSource)dependencies.analyze(condition);
                    rowType = tableRowType(table);
                    fieldOffsets = new ColumnSourceFieldOffsets(table, rowType);
                }
                stream.operator = API.select_HKeyOrdered(stream.operator,
                                                         rowType,
                                                         oldPartialAssembler.assembleExpression(condition,
                                                                 fieldOffsets));
            }
            return stream;
        }

        protected RowStream assembleFlatten(Flatten flatten) {
            RowStream stream = assembleStream(flatten.getInput());
            List<TableNode> tableNodes = flatten.getTableNodes();
            TableNode tableNode = tableNodes.get(0);
            RowType tableRowType = tableRowType(tableNode);
            stream.rowType = tableRowType;
            int ntables = tableNodes.size();
            if (ntables == 1) {
                TableSource tableSource = flatten.getTableSources().get(0);
                if (tableSource != null)
                    stream.fieldOffsets = new ColumnSourceFieldOffsets(tableSource, 
                                                                       tableRowType);
            }
            else {
                Flattened flattened = new Flattened();
                flattened.addTable(tableRowType, flatten.getTableSources().get(0));
                for (int i = 1; i < ntables; i++) {
                    tableNode = tableNodes.get(i);
                    tableRowType = tableRowType(tableNode);
                    flattened.addTable(tableRowType, flatten.getTableSources().get(i));
                    API.JoinType flattenType = null;
                    switch (flatten.getJoinTypes().get(i-1)) {
                    case INNER:
                        flattenType = API.JoinType.INNER_JOIN;
                        break;
                    case LEFT:
                        flattenType = API.JoinType.LEFT_JOIN;
                        break;
                    case RIGHT:
                        flattenType = API.JoinType.RIGHT_JOIN;
                        break;
                    case FULL_OUTER:
                        flattenType = API.JoinType.FULL_JOIN;
                        break;
                    }
                    stream.operator = API.flatten_HKeyOrdered(stream.operator, 
                                                              stream.rowType,
                                                              tableRowType,
                                                              flattenType);
                    stream.rowType = stream.operator.rowType();
                }
                flattened.setRowType(stream.rowType);
                stream.fieldOffsets = flattened;
            }
            if (stream.unknownTypesPresent) {
                stream.operator = API.filter_Default(stream.operator,
                                                     Collections.singletonList(stream.rowType));
                stream.unknownTypesPresent = false;
            }
            return stream;
        }

        protected RowStream assembleAncestorLookup(AncestorLookup ancestorLookup) {
            RowStream stream = assembleStream(ancestorLookup.getInput());
            GroupTable groupTable = ancestorLookup.getDescendant().getGroup().getGroupTable();
            RowType inputRowType = stream.rowType; // The index row type.
            API.InputPreservationOption flag = API.InputPreservationOption.DISCARD_INPUT;
            if (!(inputRowType instanceof IndexRowType)) {
                // Getting from branch lookup.
                inputRowType = tableRowType(ancestorLookup.getDescendant());
                flag = API.InputPreservationOption.KEEP_INPUT;
            }
            List<UserTableRowType> ancestorTypes =
                new ArrayList<UserTableRowType>(ancestorLookup.getAncestors().size());
            for (TableNode table : ancestorLookup.getAncestors()) {
                ancestorTypes.add(tableRowType(table));
            }
            stream.operator = API.ancestorLookup_Default(stream.operator,
                                                         groupTable,
                                                         inputRowType,
                                                         ancestorTypes,
                                                         flag);
            stream.rowType = null;
            stream.fieldOffsets = null;
            return stream;
        }

        protected RowStream assembleBranchLookup(BranchLookup branchLookup) {
            RowStream stream;
            GroupTable groupTable = branchLookup.getSource().getGroup().getGroupTable();
            if (branchLookup.getInput() != null) {
                stream = assembleStream(branchLookup.getInput());
                RowType inputRowType = stream.rowType; // The index row type.
                API.InputPreservationOption flag = API.InputPreservationOption.DISCARD_INPUT;
                if (!(inputRowType instanceof IndexRowType)) {
                    // Getting from ancestor lookup.
                    inputRowType = tableRowType(branchLookup.getSource());
                    flag = API.InputPreservationOption.KEEP_INPUT;
                }
                stream.operator = API.branchLookup_Default(stream.operator, 
                                                           groupTable, 
                                                           inputRowType,
                                                           tableRowType(branchLookup.getBranch()), 
                                                           flag);
            }
            else {
                stream = new RowStream();
                API.InputPreservationOption flag = API.InputPreservationOption.KEEP_INPUT;
                stream.operator = API.branchLookup_Nested(groupTable, 
                                                          tableRowType(branchLookup.getSource()),
                                                          tableRowType(branchLookup.getAncestor()),
                                                          tableRowType(branchLookup.getBranch()), 
                                                          flag,
                                                          currentBindingPosition());
                
            }
            stream.rowType = null;
            stream.unknownTypesPresent = true;
            stream.fieldOffsets = null;
            return stream;
        }

        protected RowStream assembleMapJoin(MapJoin mapJoin) {
            int pos = pushBoundRow(null); // Allocate slot in case loops in outer.
            RowStream ostream = assembleStream(mapJoin.getOuter());
            boundRows.set(pos, ostream.fieldOffsets);
            RowStream stream = assembleStream(mapJoin.getInner());
            stream.operator = API.map_NestedLoops(ostream.operator, 
                                                  stream.operator,
                                                  currentBindingPosition());
            popBoundRow();
            return stream;
        }

        protected RowStream assembleProduct(Product product) {
            UserTableRowType ancestorRowType = null;
            if (product.getAncestor() != null)
                ancestorRowType = tableRowType(product.getAncestor());
            RowStream pstream = new RowStream();
            Flattened flattened = new Flattened();
            int nbound = 0;
            for (PlanNode subplan : product.getSubplans()) {
                if (pstream.operator != null) {
                    // The actual bound row is the branch row, which
                    // we don't access directly. Just give each
                    // product a separate position; nesting doesn't
                    // matter.
                    pushBoundRow(null);
                    nbound++;
                }
                RowStream stream = assembleStream(subplan);
                if (pstream.operator == null) {
                    pstream.operator = stream.operator;
                    pstream.rowType = stream.rowType;
                }
                else {
                    pstream.operator = API.product_NestedLoops(pstream.operator,
                                                               stream.operator,
                                                               pstream.rowType,
                                                               ancestorRowType,
                                                               stream.rowType,
                                                               currentBindingPosition());
                    pstream.rowType = pstream.operator.rowType();
                }
                if (stream.fieldOffsets instanceof ColumnSourceFieldOffsets) {
                    TableSource table = ((ColumnSourceFieldOffsets)
                                         stream.fieldOffsets).getTable();
                    flattened.addTable(tableRowType(table), table);
                }
                else {
                    flattened.product((Flattened)stream.fieldOffsets);
                }
            }
            while (nbound > 0) {
                popBoundRow();
                nbound--;
            }
            flattened.setRowType(pstream.rowType);
            pstream.fieldOffsets = flattened;
            return pstream;
        }

        protected RowStream assembleAggregateSource(AggregateSource aggregateSource) {
            AggregateSource.Implementation impl = aggregateSource.getImplementation();
            if (impl == null)
              impl = AggregateSource.Implementation.SORT;
            int nkeys = aggregateSource.getNGroupBy();
            RowStream stream;
            switch (impl) {
            case COUNT_STAR:
            case COUNT_TABLE_STATUS:
                {
                    assert !aggregateSource.isProjectSplitOff();
                    assert ((nkeys == 0) &&
                            (aggregateSource.getNAggregates() == 1));
                    if (impl == AggregateSource.Implementation.COUNT_STAR) {
                        stream = assembleStream(aggregateSource.getInput());
                        // TODO: Could be removed, since aggregate_Partial works as well.
                        stream.operator = API.count_Default(stream.operator, 
                                                            stream.rowType);
                    }
                    else {
                        stream = new RowStream();
                        stream.operator = API.count_TableStatus(tableRowType(aggregateSource.getTable()));
                    }
                    stream.rowType = stream.operator.rowType();
                    stream.fieldOffsets = new ColumnSourceFieldOffsets(aggregateSource, 
                                                                       stream.rowType);
                    return stream;
                }                
            }
            assert aggregateSource.isProjectSplitOff();
            stream = assembleStream(aggregateSource.getInput());
            switch (impl) {
            case PRESORTED:
            case UNGROUPED:
                break;
            case FIRST_FROM_INDEX:
                {
                    assert ((nkeys == 0) &&
                            (aggregateSource.getNAggregates() == 1));
                    stream.operator = API.limit_Default(stream.operator, 1);
                    stream = assembleNullIfEmpty(stream);
                    stream.fieldOffsets = new ColumnSourceFieldOffsets(aggregateSource, 
                                                                       stream.rowType);
                    return stream;
                }
            default:
                // TODO: Could pre-aggregate now in PREAGGREGATE_RESORT case.
                assembleSort(stream, nkeys, aggregateSource.getInput(),
                             API.SortOption.PRESERVE_DUPLICATES);
                break;
            }
            PartialAssembler<?> partialAssembler = usePValues ? newPartialAssembler : oldPartialAssembler;
            stream.operator = partialAssembler.assembleAggregates(stream.operator, stream.rowType, nkeys,
                    aggregateSource.getAggregateFunctions());
            stream.rowType = stream.operator.rowType();
            stream.fieldOffsets = new ColumnSourceFieldOffsets(aggregateSource,
                                                               stream.rowType);
            return stream;
        }

        protected RowStream assembleDistinct(Distinct distinct) {
            Distinct.Implementation impl = distinct.getImplementation();
            if (impl == Distinct.Implementation.EXPLICIT_SORT) {
                PlanNode input = distinct.getInput();
                if (input instanceof Sort) {
                    // Explicit Sort is still there; combine.
                    return assembleSort((Sort)input, distinct.getOutput(), 
                                        API.SortOption.SUPPRESS_DUPLICATES);
                }
                // Sort removed but Distinct remains.
                impl = Distinct.Implementation.PRESORTED;
            }
            RowStream stream = assembleStream(distinct.getInput());
            if (impl == null)
              impl = Distinct.Implementation.SORT;
            switch (impl) {
            case PRESORTED:
                stream.operator = API.distinct_Partial(stream.operator, stream.rowType, usePValues);
                break;
            default:
                assembleSort(stream, stream.rowType.nFields(), distinct.getInput(),
                             API.SortOption.SUPPRESS_DUPLICATES);
                break;
            }
            return stream;
        }

        protected RowStream assembleSort(Sort sort) {
            return assembleSort(sort, 
                                sort.getOutput(), API.SortOption.PRESERVE_DUPLICATES);
        }

        protected RowStream assembleSort(Sort sort, 
                                         PlanNode output, API.SortOption sortOption) {
            RowStream stream = assembleStream(sort.getInput());
            API.Ordering ordering = API.ordering();
            for (OrderByExpression orderBy : sort.getOrderBy()) {
                Expression expr = oldPartialAssembler.assembleExpression(orderBy.getExpression(),
                        stream.fieldOffsets);
                ordering.append(expr, orderBy.isAscending());
            }
            assembleSort(stream, ordering, sort.getInput(), output, sortOption);
            return stream;
        }

        protected void assembleSort(RowStream stream, API.Ordering ordering,
                                    PlanNode input, PlanNode output, 
                                    API.SortOption sortOption) {
            int maxrows = -1;
            if (output instanceof Project) {
                output = output.getOutput();
            }
            if (output instanceof Limit) {
                Limit limit = (Limit)output;
                if (!limit.isOffsetParameter() && !limit.isLimitParameter()) {
                    maxrows = limit.getOffset() + limit.getLimit();
                }
            }
            else if (input instanceof ExpressionsSource) {
                ExpressionsSource expressionsSource = (ExpressionsSource)input;
                maxrows = expressionsSource.getExpressions().size();
            }
            if ((maxrows >= 0) && (maxrows <= INSERTION_SORT_MAX_LIMIT))
                stream.operator = API.sort_InsertionLimited(stream.operator, stream.rowType,
                                                            ordering, sortOption, maxrows);
            else
                stream.operator = API.sort_Tree(stream.operator, stream.rowType, ordering, sortOption);
        }

        protected void assembleSort(RowStream stream, int nkeys, PlanNode input,
                                    API.SortOption sortOption) {
            API.Ordering ordering = API.ordering();
            for (int i = 0; i < nkeys; i++) {
                ordering.append(Expressions.field(stream.rowType, i), true);
            }
            assembleSort(stream, ordering, input, null, sortOption);
        }

        protected RowStream assembleLimit(Limit limit) {
            RowStream stream = assembleStream(limit.getInput());
            int nlimit = limit.getLimit();
            if ((nlimit < 0) && !limit.isLimitParameter())
                nlimit = Integer.MAX_VALUE; // Slight disagreement in saying unlimited.
            stream.operator = API.limit_Default(stream.operator, 
                                                limit.getOffset(), limit.isOffsetParameter(),
                                                nlimit, limit.isLimitParameter(), usePValues);
            return stream;
        }

        protected RowStream assembleNullIfEmpty(NullIfEmpty nullIfEmpty) {
            RowStream stream = assembleStream(nullIfEmpty.getInput());
            return assembleNullIfEmpty(stream);
        }

        protected RowStream assembleNullIfEmpty(RowStream stream) {
            Expression[] nulls = new Expression[stream.rowType.nFields()];
            Arrays.fill(nulls, LiteralExpression.forNull());
            stream.operator = API.ifEmpty_Default(stream.operator, stream.rowType, Arrays.asList(nulls), API.InputPreservationOption.KEEP_INPUT);
            return stream;
        }

        protected RowStream assembleOnlyIfEmpty(OnlyIfEmpty onlyIfEmpty) {
            RowStream stream = assembleStream(onlyIfEmpty.getInput());
            stream.operator = API.limit_Default(stream.operator, 0, false, 1, false, usePValues);
            // Nulls here have no semantic meaning, but they're easier than trying to
            // figure out an interesting non-null value for each
            // AkType in the row. All that really matters is that the
            // row is there.
            Expression[] nulls = new Expression[stream.rowType.nFields()];
            Arrays.fill(nulls, LiteralExpression.forNull());
            stream.operator = API.ifEmpty_Default(stream.operator, stream.rowType, Arrays.asList(nulls), API.InputPreservationOption.DISCARD_INPUT);
            return stream;
        }

        protected RowStream assembleUsingBloomFilter(UsingBloomFilter usingBloomFilter) {
            BloomFilter bloomFilter = usingBloomFilter.getBloomFilter();
            int pos = pushHashTable(bloomFilter);
            RowStream lstream = assembleStream(usingBloomFilter.getLoader());
            RowStream stream = assembleStream(usingBloomFilter.getInput());
            stream.operator = API.using_BloomFilter(lstream.operator,
                                                    lstream.rowType,
                                                    bloomFilter.getEstimatedSize(),
                                                    pos,
                                                    stream.operator,
                                                    usePValues);
            popHashTable(bloomFilter);
            return stream;
        }

        protected RowStream assembleBloomFilterFilter(BloomFilterFilter bloomFilterFilter) {
            BloomFilter bloomFilter = bloomFilterFilter.getBloomFilter();
            int pos = getHashTablePosition(bloomFilter);
            RowStream stream = assembleStream(bloomFilterFilter.getInput());
            boundRows.set(pos, stream.fieldOffsets);
            RowStream cstream = assembleStream(bloomFilterFilter.getCheck());
            boundRows.set(pos, null);
            List<Expression> fields = oldPartialAssembler.assembleExpressions(bloomFilterFilter.getLookupExpressions(),
                                                          stream.fieldOffsets);
            stream.operator = API.select_BloomFilter(stream.operator,
                                                     cstream.operator,
                                                     fields,
                                                     pos);
            return stream;
        }        

        protected RowStream assembleProject(Project project) {
            RowStream stream = assembleStream(project.getInput());
            List<Expression> oldProjections;
            List<? extends TPreparedExpression> pExpressions;
            if (usePValues) {
                pExpressions = newPartialAssembler.assembleExpressions(project.getFields(), stream.fieldOffsets);
                oldProjections = null;
            }
            else {
                pExpressions = null;
                oldProjections = oldPartialAssembler.assembleExpressions(project.getFields(), stream.fieldOffsets);
            }
            stream.operator = API.project_Default(stream.operator,
                                                  stream.rowType,
                                                  oldProjections,
                                                  pExpressions);
            stream.rowType = stream.operator.rowType();
            stream.fieldOffsets = new ColumnSourceFieldOffsets(project,
                                                               stream.rowType);
            return stream;
        }

        // Get a list of result columns based on ResultSet expression names.
        protected List<PhysicalResultColumn> getResultColumns(List<ResultField> fields) {
            List<PhysicalResultColumn> columns = 
                new ArrayList<PhysicalResultColumn>(fields.size());
            for (ResultField field : fields) {
                columns.add(rulesContext.getResultColumn(field));
            }
            return columns;
        }

        // Get a list of result columns for unnamed columns.
        // This would correspond to top-level VALUES, which the parser
        // does not currently support.
        protected List<PhysicalResultColumn> getResultColumns(int ncols) {
            List<PhysicalResultColumn> columns = 
                new ArrayList<PhysicalResultColumn>(ncols);
            for (int i = 0; i < ncols; i++) {
                columns.add(rulesContext.getResultColumn(new ResultField("column" + (i+1))));
            }
            return columns;
        }

        // Generate key range bounds.
        protected IndexKeyRange assembleIndexKeyRange(SingleIndexScan index, ColumnExpressionToIndex fieldOffsets) {
            return assembleIndexKeyRange(
                    index,
                    fieldOffsets,
                    index.getLowComparand(),
                    index.isLowInclusive(),
                    index.getHighComparand(),
                    index.isHighInclusive()
            );
        }

        protected IndexKeyRange assembleIndexKeyRange(SingleIndexScan index, ColumnExpressionToIndex fieldOffsets,
                                                      RangeSegment segment)
        {
            return assembleIndexKeyRange(
                    index,
                    fieldOffsets,
                    segment.getStart().getValueExpression(),
                    segment.getStart().isInclusive(),
                    segment.getEnd().getValueExpression(),
                    segment.getEnd().isInclusive()
            );
        }

        private IndexKeyRange assembleIndexKeyRange(SingleIndexScan index,
                                                    ColumnExpressionToIndex fieldOffsets,
                                                    ExpressionNode lowComparand,
                                                    boolean lowInclusive, ExpressionNode highComparand,
                                                    boolean highInclusive)
        {
            List<ExpressionNode> equalityComparands = index.getEqualityComparands();
            IndexRowType indexRowType = getIndexRowType(index);
            if ((equalityComparands == null) &&
                    (lowComparand == null) && (highComparand == null))
                return IndexKeyRange.unbounded(indexRowType);

            int nkeys = 0;
            if (equalityComparands != null)
                nkeys = equalityComparands.size();
            if ((lowComparand != null) || (highComparand != null))
                nkeys++;
            Expression[] keys = new Expression[nkeys];
            Arrays.fill(keys, LiteralExpression.forNull());

            int kidx = 0;
            if (equalityComparands != null) {
                for (ExpressionNode comp : equalityComparands) {
                    if (comp != null)
                        keys[kidx] = oldPartialAssembler.assembleExpression(comp, fieldOffsets);
                    kidx++;
                }
            }

            if ((lowComparand == null) && (highComparand == null)) {
                IndexBound eq = getIndexBound(index.getIndex(), keys, kidx);
                return IndexKeyRange.bounded(indexRowType, eq, true, eq, true);
            }
            else {
                Expression[] lowKeys = null, highKeys = null;
                boolean lowInc = false, highInc = false;
                int lidx = kidx, hidx = kidx;
                if ((lidx > 0) || (lowComparand != null)) {
                    lowKeys = keys;
                    if ((hidx > 0) || (highComparand != null)) {
                        highKeys = new Expression[nkeys];
                        System.arraycopy(keys, 0, highKeys, 0, nkeys);
                    }
                }
                else if (highComparand != null) {
                    highKeys = keys;
                }
                if (lowComparand != null) {
                    lowKeys[lidx++] = oldPartialAssembler.assembleExpression(lowComparand, fieldOffsets);
                    lowInc = lowInclusive;
                }
                if (highComparand != null) {
                    highKeys[hidx++] = oldPartialAssembler.assembleExpression(highComparand, fieldOffsets);
                    highInc = highInclusive;
                }
                int bounded = lidx > hidx ? lidx : hidx;
                IndexBound lo = getIndexBound(index.getIndex(), lowKeys, bounded);
                IndexBound hi = getIndexBound(index.getIndex(), highKeys, bounded);
                assert lo != null || hi != null;
                if (lo == null) {
                    lo = getNullIndexBound(index.getIndex(), hidx);
                }
                if (hi == null) {
                    hi = getNullIndexBound(index.getIndex(), lidx);
                }
                return IndexKeyRange.bounded(indexRowType, lo, lowInc, hi, highInc);
            }
        }

        protected API.Ordering assembleIndexOrdering(IndexScan index,
                                                     IndexRowType indexRowType) {
            API.Ordering ordering = API.ordering();
            List<OrderByExpression> indexOrdering = index.getOrdering();
            for (int i = 0; i < indexOrdering.size(); i++) {
                ordering.append(Expressions.field(indexRowType, i),
                                indexOrdering.get(i).isAscending());
            }
            return ordering;
        }

        protected UserTableRowType tableRowType(TableSource table) {
            return tableRowType(table.getTable());
        }

        protected UserTableRowType tableRowType(TableNode table) {
            return schema.userTableRowType(table.getTable());
        }

        protected ValuesRowType valuesRowType(AkType[] fields) {
            return schema.newValuesType(fields);
        }

        protected IndexRowType getIndexRowType(SingleIndexScan index) {
            return schema.indexRowType(index.getIndex());
        }

        /** Return an index bound for the given index and expressions.
         * @param index the index in use
         * @param keys {@link Expression}s for index lookup key
         * @param nBoundKeys number of keys actually in use
         */
        protected IndexBound getIndexBound(Index index, Expression[] keys, int nBoundKeys) {
            if (keys == null)
                return null;
            Expression[] boundKeys;
            if (nBoundKeys < keys.length) {
                boundKeys = new Expression[nBoundKeys];
                System.arraycopy(keys, 0, boundKeys, 0, nBoundKeys);
            } else {
                boundKeys = keys;
            }
            return new IndexBound(getIndexExpressionRow(index, boundKeys),
                                  getIndexColumnSelector(index, nBoundKeys));
        }

        /** Return an index bound for the given index containing all nulls.
         * @param index the index in use
         * @param nkeys number of keys actually in use
         */
        protected IndexBound getNullIndexBound(Index index, int nkeys) {
            Expression[] keys = new Expression[nkeys];
            Arrays.fill(keys, LiteralExpression.forNull());
            return new IndexBound(getIndexExpressionRow(index, keys),
                                  getIndexColumnSelector(index, nkeys));
        }

        /** Return a column selector that enables the first <code>nkeys</code> fields
         * of a row of the index's user table. */
        protected ColumnSelector getIndexColumnSelector(final Index index, 
                                                        final int nkeys) {
            assert nkeys <= index.getAllColumns().size() : index + " " + nkeys;
            return new ColumnSelector() {
                    public boolean includesColumn(int columnPosition) {
                        return columnPosition < nkeys;
                    }
                };
        }

        /** Return a {@link Row} for the given index containing the given
         * {@link Expression} values.  
         */
        protected UnboundExpressions getIndexExpressionRow(Index index, 
                                                           Expression[] keys) {
            RowType rowType = schema.indexRowType(index);
            return new RowBasedUnboundExpressions(rowType, Arrays.asList(keys));
        }

        // Get the required type for any parameters to the statement.
        protected DataTypeDescriptor[] getParameterTypes() {
            AST ast = ASTStatementLoader.getAST(planContext);
            if (ast == null)
                return null;
            List<ParameterNode> params = ast.getParameters();
            if ((params == null) || params.isEmpty())
                return null;
            int nparams = 0;
            for (ParameterNode param : params) {
                if (nparams < param.getParameterNumber() + 1)
                    nparams = param.getParameterNumber() + 1;
            }
            DataTypeDescriptor[] result = new DataTypeDescriptor[nparams];
            for (ParameterNode param : params) {
                result[param.getParameterNumber()] = param.getType();
            }        
            return result;
        }

        /* Bindings-related state */

        protected int expressionBindingsOffset, loopBindingsOffset;
        protected Stack<ColumnExpressionToIndex> boundRows = new Stack<ColumnExpressionToIndex>(); // Needs to be List<>.
        protected Map<BaseHashTable,Integer> hashTablePositions = new HashMap<BaseHashTable,Integer>();

        protected void computeBindingsOffsets() {
            expressionBindingsOffset = 0;

            // Binding positions start with parameter positions.
            AST ast = ASTStatementLoader.getAST(planContext);
            if (ast != null) {
                List<ParameterNode> params = ast.getParameters();
                if (params != null) {
                    expressionBindingsOffset = ast.getParameters().size();
                }
            }

            loopBindingsOffset = expressionBindingsOffset;
        }

        protected int pushBoundRow(ColumnExpressionToIndex boundRow) {
            int position = boundRows.size();
            boundRows.push(boundRow);
            return position;
        }

        protected void popBoundRow() {
            boundRows.pop();
        }

        protected int currentBindingPosition() {
            return loopBindingsOffset + boundRows.size() - 1;
        }

        protected int pushHashTable(BaseHashTable hashTable) {
            int position = pushBoundRow(null);
            hashTablePositions.put(hashTable, position);
            return position;
        }

        protected void popHashTable(BaseHashTable hashTable) {
            popBoundRow();
            int position = hashTablePositions.remove(hashTable);
            assert (position == boundRows.size());
        }

        protected int getHashTablePosition(BaseHashTable hashTable) {
            return hashTablePositions.get(hashTable);
        }

        class ColumnBoundRows implements ColumnExpressionContext {
            ColumnExpressionToIndex current;

            @Override
            public ColumnExpressionToIndex getCurrentRow() {
                return current;
            }

            @Override
            public List<ColumnExpressionToIndex> getBoundRows() {
                return boundRows;
            }

            @Override
            public int getExpressionBindingsOffset() {
                return expressionBindingsOffset;
            }

            @Override
            public int getLoopBindingsOffset() {
                return loopBindingsOffset;
            }
        }
        
        ColumnBoundRows columnBoundRows = new ColumnBoundRows();

        protected ColumnExpressionContext getColumnExpressionContext(ColumnExpressionToIndex current) {
            columnBoundRows.current = current;
            return columnBoundRows;
        }
    }

    // Struct for multiple value return from assembly.
    static class RowStream {
        Operator operator;
        RowType rowType;
        boolean unknownTypesPresent;
        ColumnExpressionToIndex fieldOffsets;
    }

    static abstract class BaseColumnExpressionToIndex implements ColumnExpressionToIndex {
        protected RowType rowType;

        BaseColumnExpressionToIndex(RowType rowType) {
            this.rowType = rowType;
        }

        @Override
        public RowType getRowType() {
            return rowType;
        }
    }

    // Single table-like source.
    static class ColumnSourceFieldOffsets extends BaseColumnExpressionToIndex {
        private ColumnSource source;

        public ColumnSourceFieldOffsets(ColumnSource source, RowType rowType) {
            super(rowType);
            this.source = source;
        }

        public ColumnSource getSource() {
            return source;
        }

        public TableSource getTable() {
            return (TableSource)source;
        }

        @Override
        public int getIndex(ColumnExpression column) {
            if (column.getTable() != source) 
                return -1;
            else
                return column.getPosition();
        }

        @Override
        public String toString() {
            return super.toString() + "(" + source + ")";
        }
    }

    // Index used as field source (e.g., covering).
    static class IndexFieldOffsets extends BaseColumnExpressionToIndex {
        private IndexScan index;

        public IndexFieldOffsets(IndexScan index, RowType rowType) {
            super(rowType);
            this.index = index;
        }

        @Override
        // Access field of the index row itself. 
        // (Covering index or condition before lookup.)
        public int getIndex(ColumnExpression column) {
            return index.getColumns().indexOf(column);
        }


        @Override
        public String toString() {
            return super.toString() + "(" + index + ")";
        }
    }

    // Flattened row.
    static class Flattened extends BaseColumnExpressionToIndex {
        Map<TableSource,Integer> tableOffsets = new HashMap<TableSource,Integer>();
        int nfields;
            
        Flattened() {
            super(null);        // Worked out later.
        }

        public void setRowType(RowType rowType) {
            this.rowType = rowType;
        }

        @Override
        public int getIndex(ColumnExpression column) {
            Integer tableOffset = tableOffsets.get(column.getTable());
            if (tableOffset == null)
                return -1;
            return tableOffset + column.getPosition();
        }

        public void addTable(RowType rowType, TableSource table) {
            if (table != null)
                tableOffsets.put(table, nfields);
            nfields += rowType.nFields();
        }

        // Tack on another flattened using product rules.
        public void product(final Flattened other) {
            List<TableSource> otherTables = 
                new ArrayList<TableSource>(other.tableOffsets.keySet());
            Collections.sort(otherTables,
                             new Comparator<TableSource>() {
                                 @Override
                                 public int compare(TableSource x, TableSource y) {
                                     return other.tableOffsets.get(x) - other.tableOffsets.get(y);
                                 }
                             });
            for (int i = 0; i < otherTables.size(); i++) {
                TableSource otherTable = otherTables.get(i);
                if (!tableOffsets.containsKey(otherTable)) {
                    tableOffsets.put(otherTable, nfields);
                    // Width in other.tableOffsets.
                    nfields += (((i+1 >= otherTables.size()) ?
                                 other.nfields :
                                 other.tableOffsets.get(otherTables.get(i+1))) -
                                other.tableOffsets.get(otherTable));
                }
            }
        }


        @Override
        public String toString() {
            return super.toString() + "(" + tableOffsets.keySet() + ")";
        }
    }
}
