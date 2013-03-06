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

package com.akiban.sql.optimizer.plan;

import com.akiban.ais.model.IndexColumn;

public class FullTextField extends FullTextQuery
{
    public enum Type { MATCH, PARSE, LIKE };

    private ColumnExpression column;
    private Type type;
    private ExpressionNode key;
    private IndexColumn indexColumn;
    
    public FullTextField(ColumnExpression column, Type type, ExpressionNode key) {
        this.column = column;
        this.type = type;
        this.key = key;
    }

    public ColumnExpression getColumn() {
        return column;
    }
    public Type getType() {
        return type;
    }
    public ExpressionNode getKey() {
        return key;
    }

    public IndexColumn getIndexColumn() {
        return indexColumn;
    }
    public void setIndexColumn(IndexColumn indexColumn) {
        this.indexColumn = indexColumn;
    }

    public boolean accept(ExpressionVisitor v) {
        return (column.accept(v) && key.accept(v));
    }

    public void accept(ExpressionRewriteVisitor v) {
        column = (ColumnExpression)column.accept(v);
        key = key.accept(v);
    }

    public FullTextField duplicate(DuplicateMap map) {
        return new FullTextField((ColumnExpression)column.duplicate(map),
                                 type,
                                 (ExpressionNode)key.duplicate(map));
    }
    
    @Override
    public String toString() {
        return type + "(" + column + ", " + key + ")";
    }

}
