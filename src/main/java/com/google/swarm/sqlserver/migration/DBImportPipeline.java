/* Copyright 2018 Google LLC

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package com.google.swarm.sqlserver.migration;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.coders.CoderProviders;
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.services.bigquery.model.TableRow;
import com.google.swarm.sqlserver.migration.common.BigQueryTableDestination;
import com.google.swarm.sqlserver.migration.common.BigQueryTableRowDoFn;
import com.google.swarm.sqlserver.migration.common.CreateTableMapDoFn;
import com.google.swarm.sqlserver.migration.common.DBImportPipelineOptions;
import com.google.swarm.sqlserver.migration.common.DLPTokenizationDoFn;
import com.google.swarm.sqlserver.migration.common.DeterministicKeyCoder;
import com.google.swarm.sqlserver.migration.common.SqlTable;
import com.google.swarm.sqlserver.migration.common.TableToDbRowFn;

public class DBImportPipeline {
	public static final Logger LOG = LoggerFactory.getLogger(DBImportPipeline.class);

	public static void main(String[] args) throws IOException, GeneralSecurityException {

		DBImportPipelineOptions options = PipelineOptionsFactory.fromArgs(args).withValidation()
				.as(DBImportPipelineOptions.class);

		runDBImport(options);

	}

	@SuppressWarnings("serial")
	public static void runDBImport(DBImportPipelineOptions options) {

		Pipeline p = Pipeline.create(options);

		p.getCoderRegistry()
				.registerCoderProvider(CoderProviders.fromStaticMethods(SqlTable.class, DeterministicKeyCoder.class));

		PCollection<ValueProvider<String>> jdbcString = p.apply("Check DB Properties",
				Create.of(options.getJDBCSpec()));

		PCollection<SqlTable> tableCollection = jdbcString.apply("Create Table Map",
				ParDo.of(new CreateTableMapDoFn(options.getExcludedTables(), options.getDLPConfigBucket(),
						options.getDLPConfigObject(), options.getJDBCSpec(), options.getDataSet(),
						options.as(GcpOptions.class).getProject())));

		PCollection<KV<SqlTable, TableRow>> dbRowKeyValue = tableCollection
				.apply("Create DB Rows", ParDo.of(new TableToDbRowFn(options.getJDBCSpec(), options.getOffsetCount())))
				.apply("DLP Tokenization",
						ParDo.of(new DLPTokenizationDoFn(options.as(GcpOptions.class).getProject())))
				.apply("Convert To BQ Row", ParDo.of(new BigQueryTableRowDoFn()));

		dbRowKeyValue.apply("Write to BQ",
				BigQueryIO.<KV<SqlTable, TableRow>>write().to(new BigQueryTableDestination(options.getDataSet()))
						.withFormatFunction(new SerializableFunction<KV<SqlTable, TableRow>, TableRow>() {

							@Override
							public TableRow apply(KV<SqlTable, TableRow> kv) {
								return kv.getValue();

							}
						}).withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_TRUNCATE)
						.withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED));

		p.run();
	}

}
