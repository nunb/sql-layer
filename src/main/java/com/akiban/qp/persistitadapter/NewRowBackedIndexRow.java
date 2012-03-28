/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.TableIndex;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.expression.BoundExpressions;
import com.akiban.qp.row.HKey;
import com.akiban.qp.row.Row;
import com.akiban.qp.row.RowBase;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.rowdata.FieldDef;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.types.ValueSource;
import com.akiban.server.types.FromObjectValueSource;

public class NewRowBackedIndexRow implements RowBase
{
    // Object interface

    @Override
    public String toString()
    {
        return row.toString();
    }

    // NewRowBackedIndexRow interface

    NewRowBackedIndexRow(RowType rowType, NewRow row, TableIndex index) {
        this.rowType = rowType;
        this.row = row;
        this.index = index;
        this.sources = new FromObjectValueSource[rowType.nFields()];
        for (int f = 0; f < rowType.nFields(); f++) {
            this.sources[f] = new FromObjectValueSource();
        }
    }

    // BoundExpressions interface

    @Override
    public int compareTo(BoundExpressions row, int leftStartIndex, int rightStartIndex, int fieldCount)
    {
        throw new UnsupportedOperationException();
    }

    // RowBase interface

    @Override
    public RowType rowType() {
        return rowType;
    }

    @Override
    public ValueSource eval(int i) {
        FieldDef fieldDef = (FieldDef) index.getKeyColumns().get(i).getColumn().getFieldDef();
        int fieldPos = fieldDef.getFieldIndex();
        FromObjectValueSource source = sources[i];
        if (row.isColumnNull(fieldPos)) {
            source.setNull();
        }
        source.setReflectively(row.get(fieldPos));
        return source;
    }

    @Override
    public boolean containsRealRowOf(UserTable userTable) {
        return false;
    }

    @Override
    public HKey hKey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean ancestorOf(RowBase that) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Row subRow(RowType subRowType)
    {
        throw new UnsupportedOperationException();
    }

    // Object state

    private final NewRow row;
    private final RowType rowType;
    private final TableIndex index;
    private final FromObjectValueSource[] sources;
}
