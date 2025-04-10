/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan;

import org.apache.flink.table.catalog.CatalogTable;
import org.apache.flink.table.catalog.ConnectorCatalogTable;
import org.apache.flink.table.catalog.ContextResolvedTable;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.catalog.ResolvedCatalogTable;
import org.apache.flink.table.catalog.ResolvedSchema;
import org.apache.flink.table.expressions.DefaultSqlFactory;
import org.apache.flink.table.legacy.api.TableSchema;
import org.apache.flink.table.planner.calcite.FlinkTypeFactory;
import org.apache.flink.table.planner.calcite.FlinkTypeSystem;
import org.apache.flink.table.planner.catalog.CatalogSchemaTable;
import org.apache.flink.table.planner.plan.schema.FlinkPreparingTableBase;
import org.apache.flink.table.planner.plan.stats.FlinkStatistic;
import org.apache.flink.table.planner.utils.TestTableSource;
import org.apache.flink.table.utils.CatalogManagerMocks;

import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Test for FlinkCalciteCatalogReader. */
class FlinkCalciteCatalogReaderTest {
    private final FlinkTypeFactory typeFactory =
            new FlinkTypeFactory(
                    Thread.currentThread().getContextClassLoader(), FlinkTypeSystem.INSTANCE);
    private final String tableMockName = "ts";

    private SchemaPlus rootSchemaPlus;
    private FlinkCalciteCatalogReader catalogReader;

    @BeforeEach
    void init() {
        rootSchemaPlus = CalciteSchema.createRootSchema(true, false).plus();
        Properties prop = new Properties();
        prop.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
        CalciteConnectionConfigImpl calciteConnConfig = new CalciteConnectionConfigImpl(prop);
        catalogReader =
                new FlinkCalciteCatalogReader(
                        CalciteSchema.from(rootSchemaPlus),
                        Collections.emptyList(),
                        typeFactory,
                        calciteConnConfig);
    }

    @Test
    void testGetFlinkPreparingTableBase() {
        // Mock CatalogSchemaTable.
        final ObjectIdentifier objectIdentifier = ObjectIdentifier.of("a", "b", "c");
        final ResolvedSchema schema =
                new ResolvedSchema(Collections.emptyList(), Collections.emptyList(), null);
        final CatalogTable catalogTable =
                ConnectorCatalogTable.source(
                        new TestTableSource(
                                true,
                                TableSchema.fromResolvedSchema(schema, DefaultSqlFactory.INSTANCE)),
                        true);
        final ResolvedCatalogTable resolvedCatalogTable =
                new ResolvedCatalogTable(catalogTable, schema);
        CatalogSchemaTable mockTable =
                new CatalogSchemaTable(
                        ContextResolvedTable.permanent(
                                objectIdentifier,
                                CatalogManagerMocks.createEmptyCatalog(),
                                resolvedCatalogTable),
                        FlinkStatistic.UNKNOWN(),
                        true);

        rootSchemaPlus.add(tableMockName, mockTable);
        Prepare.PreparingTable preparingTable =
                catalogReader.getTable(Collections.singletonList(tableMockName));
        assertThat(preparingTable).isInstanceOf(FlinkPreparingTableBase.class);
    }

    @Test
    void testGetNonFlinkPreparingTableBase() {
        Table nonFlinkTableMock = mock(Table.class);
        when(nonFlinkTableMock.getRowType(typeFactory)).thenReturn(mock(RelDataType.class));
        rootSchemaPlus.add(tableMockName, nonFlinkTableMock);
        Prepare.PreparingTable resultTable =
                catalogReader.getTable(Collections.singletonList(tableMockName));
        assertThat(resultTable).isNotInstanceOf(FlinkPreparingTableBase.class);
    }
}
