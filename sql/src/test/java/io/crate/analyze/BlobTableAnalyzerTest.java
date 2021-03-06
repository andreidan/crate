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
import io.crate.blob.v2.BlobIndices;
import io.crate.exceptions.InvalidTableNameException;
import io.crate.exceptions.TableAlreadyExistsException;
import io.crate.exceptions.TableUnknownException;
import io.crate.metadata.MetaDataModule;
import io.crate.metadata.Routing;
import io.crate.metadata.TableIdent;
import io.crate.metadata.blob.BlobSchemaInfo;
import io.crate.metadata.blob.BlobTableInfo;
import io.crate.metadata.information.MetaDataInformationModule;
import io.crate.metadata.sys.MetaDataSysModule;
import io.crate.testing.MockedClusterServiceModule;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.inject.Module;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BlobTableAnalyzerTest extends BaseAnalyzerTest {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    private static class TestingBlobTableInfo extends BlobTableInfo {

        private Routing routing;

        public TestingBlobTableInfo(TableIdent ident,
                                    String index,
                                    ClusterService clusterService,
                                    int numberOfShards,
                                    BytesRef numberOfReplicas,
                                    ImmutableMap<String, Object> tableParameters,
                                    BytesRef blobsPath) {
            super(ident, index, clusterService, numberOfShards, numberOfReplicas, tableParameters, blobsPath);
        }

        @Override
        public Routing getRouting(WhereClause whereClause, @Nullable String preference) {
            return routing;
        }

        public void routing(Routing routing) {
            this.routing = routing;
        }
    }

    static class TestMetaDataModule extends MetaDataModule {
        @Override
        protected void configure() {
            super.configure();
        }

        @Override
        protected void bindSchemas() {
            super.bindSchemas();

            BlobSchemaInfo schemaInfo = mock(BlobSchemaInfo.class);
            TableIdent myBlobsIdent = new TableIdent(BlobSchemaInfo.NAME, "myblobs");
            TestingBlobTableInfo myBlobsInfo = new TestingBlobTableInfo(
                myBlobsIdent,
                myBlobsIdent.indexName(),
                null,
                5,
                new BytesRef("0"),
                ImmutableMap.<String, Object>of(),
                null
            );
            myBlobsInfo.routing(SHARD_ROUTING);
            when(schemaInfo.getTableInfo(myBlobsIdent.name())).thenReturn(myBlobsInfo);
            schemaBinder.addBinding(BlobSchemaInfo.NAME).toInstance(schemaInfo);
        }
    }

    @Override
    protected List<Module> getModules() {
        List<Module> modules = super.getModules();
        modules.addAll(Arrays.<Module>asList(
            new MockedClusterServiceModule(),
            new MetaDataInformationModule(),
            new TestMetaDataModule(),
            new MetaDataSysModule()
        ));
        return modules;
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWithInvalidProperty() {
        analyze("create blob table screenshots with (foobar=1)");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWithMultipleArgsToProperty() {
        analyze("create blob table screenshots with (number_of_replicas=[1, 2])");
    }

    @Test
    public void testCreateBlobTableAutoExpand() {
        CreateBlobTableAnalyzedStatement analysis = (CreateBlobTableAnalyzedStatement) analyze(
            "create blob table screenshots clustered into 10 shards with (number_of_replicas='0-all')");

        assertThat(analysis.tableIdent().name(), is("screenshots"));
        assertThat(analysis.tableIdent().schema(), is(BlobSchemaInfo.NAME));
        assertThat(analysis.tableParameter().settings().getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 0), is(10));
        assertThat(analysis.tableParameter().settings().get(IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS), is("0-all"));
    }

    @Test
    public void testCreateBlobTableDefaultNumberOfShards() {
        CreateBlobTableAnalyzedStatement analysis = analyze("create blob table screenshots");
        assertThat(analysis.tableIdent().name(), is("screenshots"));
        assertThat(analysis.tableIdent().schema(), is(BlobSchemaInfo.NAME));
        assertThat(analysis.tableParameter().settings().getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 0), is(6));
    }

    @Test
    public void testCreateBlobTableRaisesErrorIfAlreadyExists() throws Exception {
        expectedException.expect(TableAlreadyExistsException.class);
        analyze("create blob table myblobs");
    }

    @Test
    public void testCreateBlobTable() {
        CreateBlobTableAnalyzedStatement analysis = (CreateBlobTableAnalyzedStatement) analyze(
            "create blob table screenshots clustered into 10 shards with (number_of_replicas='0-all')");

        assertThat(analysis.tableIdent().name(), is("screenshots"));
        assertThat(analysis.tableParameter().settings().getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 0), is(10));
        assertThat(analysis.tableParameter().settings().get(IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS), is("0-all"));
    }

    @Test
    public void testCreateBlobTableWithPath() {
        CreateBlobTableAnalyzedStatement analysis = (CreateBlobTableAnalyzedStatement) analyze(
            "create blob table screenshots with (blobs_path='/tmp/crate_blob_data')");

        assertThat(analysis.tableIdent().name(), is("screenshots"));
        assertThat(analysis.tableParameter().settings().get(BlobIndices.SETTING_INDEX_BLOBS_PATH), is("/tmp/crate_blob_data"));
    }

    @Test
    public void testCreateBlobTableWithPathParameter() {
        CreateBlobTableAnalyzedStatement analysis = (CreateBlobTableAnalyzedStatement) analyze(
            "create blob table screenshots with (blobs_path=?)", new Object[]{"/tmp/crate_blob_data"});

        assertThat(analysis.tableIdent().name(), is("screenshots"));
        assertThat(analysis.tableParameter().settings().get(BlobIndices.SETTING_INDEX_BLOBS_PATH), is("/tmp/crate_blob_data"));
    }

    @Test
    public void testCreateBlobTableWithPathInvalidType() {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Invalid value for argument 'blobs_path'");
        analyze("create blob table screenshots with (blobs_path=1)");
    }

    @Test
    public void testCreateBlobTableWithPathInvalidParameter() {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Invalid value for argument 'blobs_path'");
        analyze("create blob table screenshots with (blobs_path=?)", new Object[]{1});
    }

    @Test(expected = InvalidTableNameException.class)
    public void testCreateBlobTableIllegalTableName() throws Exception {
        analyze("create blob table \"blob.s\"");
    }

    @Test
    public void testDropBlobTable() {
        DropBlobTableAnalyzedStatement analysis = (DropBlobTableAnalyzedStatement) analyze("drop blob table myblobs");
        assertThat(analysis.tableIdent().name(), is("myblobs"));
        assertThat(analysis.tableIdent().schema(), is(BlobSchemaInfo.NAME));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDropBlobTableWithInvalidSchema() {
        analyze("drop blob table doc.users");
    }

    @Test
    public void testDropBlobTableWithValidSchema() {
        DropBlobTableAnalyzedStatement analysis = (DropBlobTableAnalyzedStatement) analyze("drop blob table \"blob\".myblobs");
        assertThat(analysis.tableIdent().name(), is("myblobs"));
    }

    @Test(expected = TableUnknownException.class)
    public void testDropBlobTableThatDoesNotExist() {
        analyze("drop blob table unknown");
    }

    @Test
    public void testDropBlobTableIfExists() throws Exception {
        DropBlobTableAnalyzedStatement analysis = (DropBlobTableAnalyzedStatement) analyze("drop blob table if exists myblobs");
        assertThat(analysis.dropIfExists(), is(true));
        assertThat(analysis.tableIdent().name(), is("myblobs"));
        assertThat(analysis.tableIdent().schema(), is(BlobSchemaInfo.NAME));
    }

    @Test
    public void testDropNonExistentBlobTableIfExists() throws Exception {
        DropBlobTableAnalyzedStatement analysis = (DropBlobTableAnalyzedStatement) analyze("drop blob table if exists unknown");
        assertThat(analysis.dropIfExists(), is(true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAlterBlobTableWithInvalidProperty() throws Exception {
        analyze("alter blob table myblobs set (foobar='2')");
    }

    @Test
    public void testAlterBlobTableWithReplicas() throws Exception {
        AlterBlobTableAnalyzedStatement analysis = (AlterBlobTableAnalyzedStatement) analyze("alter blob table myblobs set (number_of_replicas=2)");
        assertThat(analysis.table().ident().name(), is("myblobs"));
        assertThat(analysis.tableParameter().settings().getAsInt(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0), is(2));
    }

    @Test
    public void testAlterBlobTableWithPath() {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("Invalid property \"blobs_path\" passed to [ALTER | CREATE] TABLE statement");
        analyze("alter blob table myblobs set (blobs_path=1)");
    }

    @Test
    public void testCreateBlobTableWithParams() throws Exception {
        CreateBlobTableAnalyzedStatement analysis = (CreateBlobTableAnalyzedStatement) analyze(
            "create blob table screenshots clustered into ? shards with (number_of_replicas= ?)",
            new Object[]{2, "0-all"});

        assertThat(analysis.tableIdent().name(), is("screenshots"));
        assertThat(analysis.tableIdent().schema(), is(BlobSchemaInfo.NAME));
        assertThat(analysis.tableParameter().settings().getAsInt(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 0), is(2));
        assertThat(analysis.tableParameter().settings().get(IndexMetaData.SETTING_AUTO_EXPAND_REPLICAS), is("0-all"));
    }

    @Test
    public void testCreateBlobTableWithInvalidShardsParam() throws Exception {
        expectedException.expect(IllegalArgumentException.class);
        expectedException.expectMessage("invalid number 'foo'");
        analyze("create blob table screenshots clustered into ? shards", new Object[]{"foo"});
    }
}
