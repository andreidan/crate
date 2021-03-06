/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.analyze;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import io.crate.action.sql.SessionContext;
import io.crate.analyze.repositories.RepositorySettingsModule;
import io.crate.core.collections.Row;
import io.crate.core.collections.RowN;
import io.crate.core.collections.Rows;
import io.crate.core.collections.TreeMapBuilder;
import io.crate.metadata.*;
import io.crate.metadata.table.ColumnPolicy;
import io.crate.metadata.table.TableInfo;
import io.crate.metadata.table.TestingTableInfo;
import io.crate.operation.tablefunctions.TableFunctionModule;
import io.crate.sql.parser.SqlParser;
import io.crate.test.integration.CrateUnitTest;
import io.crate.types.ArrayType;
import io.crate.types.DataTypes;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.inject.Binder;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static io.crate.testing.TestingHelpers.newMockedThreadPool;

public abstract class BaseAnalyzerTest extends CrateUnitTest {

    static final Routing SHARD_ROUTING = new Routing(TreeMapBuilder.<String, Map<String, List<Integer>>>newMapBuilder()
        .put("nodeOne", TreeMapBuilder.<String, List<Integer>>newMapBuilder().put("t1", Arrays.asList(1, 2)).map())
        .put("nodeTow", TreeMapBuilder.<String, List<Integer>>newMapBuilder().put("t1", Arrays.asList(3, 4)).map())
        .map());

    public static final TableIdent USER_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "users");

    public static final TableInfo USER_TABLE_INFO = TestingTableInfo.builder(USER_TABLE_IDENT, SHARD_ROUTING)
        .add("id", DataTypes.LONG, null)
        .add("other_id", DataTypes.LONG, null)
        .add("name", DataTypes.STRING, null)
        .add("text", DataTypes.STRING, null, Reference.IndexType.ANALYZED)
        .add("no_index", DataTypes.STRING, null, Reference.IndexType.NO)
        .add("details", DataTypes.OBJECT, null)
        .add("awesome", DataTypes.BOOLEAN, null)
        .add("counters", new ArrayType(DataTypes.LONG), null)
        .add("friends", new ArrayType(DataTypes.OBJECT), null, ColumnPolicy.DYNAMIC)
        .add("friends", DataTypes.LONG, Arrays.asList("id"))
        .add("friends", new ArrayType(DataTypes.STRING), Arrays.asList("groups"))
        .add("tags", new ArrayType(DataTypes.STRING), null)
        .add("bytes", DataTypes.BYTE, null)
        .add("shorts", DataTypes.SHORT, null)
        .add("shape", DataTypes.GEO_SHAPE)
        .add("ints", DataTypes.INTEGER, null)
        .add("floats", DataTypes.FLOAT, null)
        .addIndex(ColumnIdent.fromPath("name_text_ft"), Reference.IndexType.ANALYZED)
        .addPrimaryKey("id")
        .clusteredBy("id")
        .build();
    public static final TableIdent USER_TABLE_IDENT_MULTI_PK = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "users_multi_pk");
    public static final TableInfo USER_TABLE_INFO_MULTI_PK = TestingTableInfo.builder(USER_TABLE_IDENT_MULTI_PK, SHARD_ROUTING)
        .add("id", DataTypes.LONG, null)
        .add("name", DataTypes.STRING, null)
        .add("details", DataTypes.OBJECT, null)
        .add("awesome", DataTypes.BOOLEAN, null)
        .add("friends", new ArrayType(DataTypes.OBJECT), null, ColumnPolicy.DYNAMIC)
        .addPrimaryKey("id")
        .addPrimaryKey("name")
        .clusteredBy("id")
        .build();
    static final TableIdent USER_TABLE_IDENT_CLUSTERED_BY_ONLY = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "users_clustered_by_only");
    static final TableInfo USER_TABLE_INFO_CLUSTERED_BY_ONLY = TestingTableInfo.builder(USER_TABLE_IDENT_CLUSTERED_BY_ONLY, SHARD_ROUTING)
        .add("id", DataTypes.LONG, null)
        .add("name", DataTypes.STRING, null)
        .add("details", DataTypes.OBJECT, null)
        .add("awesome", DataTypes.BOOLEAN, null)
        .add("friends", new ArrayType(DataTypes.OBJECT), null, ColumnPolicy.DYNAMIC)
        .clusteredBy("id")
        .build();
    static final TableIdent USER_TABLE_REFRESH_INTERVAL_BY_ONLY = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "user_refresh_interval");
    static final TableInfo USER_TABLE_INFO_REFRESH_INTERVAL_BY_ONLY = TestingTableInfo.builder(USER_TABLE_REFRESH_INTERVAL_BY_ONLY, SHARD_ROUTING)
        .add("id", DataTypes.LONG, null)
        .add("content", DataTypes.STRING, null)
        .clusteredBy("id")
        .build();
    static final TableIdent NESTED_PK_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "nested_pk");
    static final TableInfo NESTED_PK_TABLE_INFO = TestingTableInfo.builder(NESTED_PK_TABLE_IDENT, SHARD_ROUTING)
        .add("id", DataTypes.LONG, null)
        .add("o", DataTypes.OBJECT, null, ColumnPolicy.DYNAMIC)
        .add("o", DataTypes.BYTE, Arrays.asList("b"))
        .addPrimaryKey("id")
        .addPrimaryKey("o.b")
        .clusteredBy("o.b")
        .build();
    static final TableIdent TEST_PARTITIONED_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "parted");
    static final TableInfo TEST_PARTITIONED_TABLE_INFO = new TestingTableInfo.Builder(
        TEST_PARTITIONED_TABLE_IDENT, new Routing(ImmutableMap.<String, Map<String, List<Integer>>>of()))
        .add("id", DataTypes.INTEGER, null)
        .add("name", DataTypes.STRING, null)
        .add("date", DataTypes.TIMESTAMP, null, true)
        .add("obj", DataTypes.OBJECT, null, ColumnPolicy.DYNAMIC)
        // add 3 partitions/simulate already done inserts
        .addPartitions(
            new PartitionName("parted", Arrays.asList(new BytesRef("1395874800000"))).asIndexName(),
            new PartitionName("parted", Arrays.asList(new BytesRef("1395961200000"))).asIndexName(),
            new PartitionName("parted", new ArrayList<BytesRef>() {{
                add(null);
            }}).asIndexName())
        .build();
    static final TableIdent TEST_MULTIPLE_PARTITIONED_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "multi_parted");
    static final TableInfo TEST_MULTIPLE_PARTITIONED_TABLE_INFO = new TestingTableInfo.Builder(
        TEST_MULTIPLE_PARTITIONED_TABLE_IDENT, new Routing(ImmutableMap.<String, Map<String, List<Integer>>>of()))
        .add("id", DataTypes.INTEGER, null)
        .add("date", DataTypes.TIMESTAMP, null, true)
        .add("num", DataTypes.LONG, null)
        .add("obj", DataTypes.OBJECT, null, ColumnPolicy.DYNAMIC)
        .add("obj", DataTypes.STRING, Arrays.asList("name"), true)
        // add 3 partitions/simulate already done inserts
        .addPartitions(
            new PartitionName("multi_parted", Arrays.asList(new BytesRef("1395874800000"), new BytesRef("0"))).asIndexName(),
            new PartitionName("multi_parted", Arrays.asList(new BytesRef("1395961200000"), new BytesRef("-100"))).asIndexName(),
            new PartitionName("multi_parted", Arrays.asList(null, new BytesRef("-100"))).asIndexName())
        .build();
    static final TableIdent TEST_NESTED_PARTITIONED_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "nested_parted");
    static final TableInfo TEST_NESTED_PARTITIONED_TABLE_INFO = new TestingTableInfo.Builder(
        TEST_NESTED_PARTITIONED_TABLE_IDENT, new Routing(ImmutableMap.<String, Map<String, List<Integer>>>of()))
        .add("id", DataTypes.INTEGER, null)
        .add("date", DataTypes.TIMESTAMP, null, true)
        .add("obj", DataTypes.OBJECT, null, ColumnPolicy.DYNAMIC)
        .add("obj", DataTypes.STRING, Arrays.asList("name"), true)
        // add 3 partitions/simulate already done inserts
        .addPartitions(
            new PartitionName("nested_parted", Arrays.asList(new BytesRef("1395874800000"), new BytesRef("Trillian"))).asIndexName(),
            new PartitionName("nested_parted", Arrays.asList(new BytesRef("1395961200000"), new BytesRef("Ford"))).asIndexName(),
            new PartitionName("nested_parted", Arrays.asList(null, new BytesRef("Zaphod"))).asIndexName())
        .build();
    static final TableIdent TEST_DOC_TRANSACTIONS_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "transactions");
    static final TableInfo TEST_DOC_TRANSACTIONS_TABLE_INFO = new TestingTableInfo.Builder(
        TEST_DOC_TRANSACTIONS_TABLE_IDENT, new Routing(ImmutableMap.<String, Map<String, List<Integer>>>of()))
        .add("id", DataTypes.LONG, null)
        .add("sender", DataTypes.STRING, null)
        .add("recipient", DataTypes.STRING, null)
        .add("amount", DataTypes.DOUBLE, null)
        .add("timestamp", DataTypes.TIMESTAMP, null)
        .build();
    static final TableIdent DEEPLY_NESTED_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "deeply_nested");
    static final TableInfo DEEPLY_NESTED_TABLE_INFO = new TestingTableInfo.Builder(
        DEEPLY_NESTED_TABLE_IDENT, new Routing(ImmutableMap.<String, Map<String, List<Integer>>>of()))
        .add("details", DataTypes.OBJECT, null, ColumnPolicy.DYNAMIC)
        .add("details", DataTypes.BOOLEAN, Arrays.asList("awesome"))
        .add("details", DataTypes.OBJECT, Arrays.asList("stuff"), ColumnPolicy.DYNAMIC)
        .add("details", DataTypes.STRING, Arrays.asList("stuff", "name"))
        .add("details", new ArrayType(DataTypes.OBJECT), Arrays.asList("arguments"))
        .add("details", DataTypes.DOUBLE, Arrays.asList("arguments", "quality"))
        .add("details", DataTypes.STRING, Arrays.asList("arguments", "name"))
        .add("tags", new ArrayType(DataTypes.OBJECT), null)
        .add("tags", DataTypes.STRING, Arrays.asList("name"))
        .add("tags", new ArrayType(DataTypes.OBJECT), Arrays.asList("metadata"))
        .add("tags", DataTypes.LONG, Arrays.asList("metadata", "id"))
        .build();

    public static final TableIdent IGNORED_NESTED_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "ignored_nested");
    public static final TableInfo IGNORED_NESTED_TABLE_INFO = new TestingTableInfo.Builder(
        IGNORED_NESTED_TABLE_IDENT, new Routing(ImmutableMap.<String, Map<String, List<Integer>>>of()))
        .add("details", DataTypes.OBJECT, null, ColumnPolicy.IGNORED)
        .build();

    static final TableIdent TEST_DOC_LOCATIONS_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "locations");
    static final TableInfo TEST_DOC_LOCATIONS_TABLE_INFO = TestingTableInfo.builder(TEST_DOC_LOCATIONS_TABLE_IDENT, SHARD_ROUTING)
        .add("id", DataTypes.LONG, null)
        .add("loc", DataTypes.GEO_POINT, null)
        .build();

    static final TableInfo TEST_CLUSTER_BY_STRING_TABLE_INFO = TestingTableInfo.builder(new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "bystring"), SHARD_ROUTING)
        .add("name", DataTypes.STRING, null)
        .add("score", DataTypes.DOUBLE, null)
        .addPrimaryKey("name")
        .clusteredBy("name")
        .build();

    protected Injector injector;
    Analyzer analyzer;


    private ThreadPool threadPool;

    protected <T extends AnalyzedStatement> T analyze(String statement) {
        //noinspection unchecked
        return (T) analysis(statement).analyzedStatement();
    }

    protected <T extends AnalyzedStatement> T analyze(String statement, Object[] params) {
        //noinspection unchecked
        return (T) analysis(statement, params).analyzedStatement();
    }

    protected <T extends AnalyzedStatement> T analyze(String statement, Object[][] bulkArgs) {
        //noinspection unchecked
        return (T) analysis(statement, bulkArgs).analyzedStatement();
    }

    protected Analysis analysis(String statement) {
        return analysis(statement, new Object[0]);
    }

    protected Analysis analysis(String statement, Object[][] bulkArgs) {
        return analyzer.boundAnalyze(SqlParser.createStatement(statement),
            SessionContext.SYSTEM_SESSION,
            new ParameterContext(Row.EMPTY, Rows.of(bulkArgs)));
    }

    protected Analysis analysis(String statement, Object[] params) {
        return analyzer.boundAnalyze(SqlParser.createStatement(statement),
            SessionContext.SYSTEM_SESSION,
            new ParameterContext(new RowN(params), Collections.<Row>emptyList()));
    }

    protected List<Module> getModules() {
        return Lists.<Module>newArrayList(
            new RepositorySettingsModule()
        );
    }

    @Before
    public void prepareModules() throws Exception {
        threadPool = newMockedThreadPool();
        ModulesBuilder builder = new ModulesBuilder();
        builder.add(new TableFunctionModule());
        builder.add(new Module() {
            @Override
            public void configure(Binder binder) {
                binder.bind(ThreadPool.class).toInstance(threadPool);
            }
        });
        for (Module m : getModules()) {
            builder.add(m);
        }
        injector = builder.createInjector();
        analyzer = injector.getInstance(Analyzer.class);
    }

    @After
    public void tearDownThreadPool() throws Exception {
        threadPool.shutdown();
        threadPool.awaitTermination(1, TimeUnit.SECONDS);
    }
}
