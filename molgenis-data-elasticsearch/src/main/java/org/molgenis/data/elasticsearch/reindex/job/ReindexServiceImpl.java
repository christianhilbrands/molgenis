package org.molgenis.data.elasticsearch.reindex.job;

import static java.time.OffsetDateTime.now;
import static java.util.Date.from;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.molgenis.data.elasticsearch.reindex.meta.ReindexJobExecutionMeta.REINDEX_JOB_EXECUTION;
import static org.molgenis.data.jobs.JobExecution.Status.SUCCESS;
import static org.molgenis.data.jobs.JobExecutionMetaData.END_DATE;
import static org.molgenis.data.jobs.JobExecutionMetaData.STATUS;
import static org.molgenis.data.reindex.meta.ReindexActionJobMetaData.REINDEX_ACTION_JOB;
import static org.molgenis.data.reindex.meta.ReindexActionMetaData.ENTITY_FULL_NAME;
import static org.molgenis.data.reindex.meta.ReindexActionMetaData.REINDEX_ACTION;
import static org.molgenis.data.reindex.meta.ReindexActionMetaData.REINDEX_ACTION_GROUP;
import static org.molgenis.security.core.runas.RunAsSystemProxy.runAsSystem;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

import org.molgenis.data.DataService;
import org.molgenis.data.Entity;
import org.molgenis.data.reindex.meta.ReindexActionJob;
import org.molgenis.data.support.QueryImpl;
import org.molgenis.security.core.runas.RunAsSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class ReindexServiceImpl implements ReindexService
{
	private static final Logger LOG = LoggerFactory.getLogger(ReindexServiceImpl.class);

	private final DataService dataService;
	private final ReindexJobFactory reindexJobFactory;
	private final ReindexJobExecutionFactory reindexJobExecutionFactory;

	/**
	 * The {@link ReindexJob}s are executed on this thread.
	 */
	private final ExecutorService executorService;
	private final IndexStatus indexStatus = new IndexStatus();

	public ReindexServiceImpl(DataService dataService, ReindexJobFactory reindexJobFactory,
			ReindexJobExecutionFactory reindexJobExecutionFactory, ExecutorService executorService)
	{
		this.dataService = requireNonNull(dataService);
		this.reindexJobFactory = requireNonNull(reindexJobFactory);
		this.reindexJobExecutionFactory = requireNonNull(reindexJobExecutionFactory);
		this.executorService = requireNonNull(executorService);
	}

	@Override
	@RunAsSystem
	public void rebuildIndex(String transactionId)
	{
		LOG.trace("Reindex transaction with id {}...", transactionId);
		ReindexActionJob reindexActionJob = dataService
				.findOneById(REINDEX_ACTION_JOB, transactionId, ReindexActionJob.class);

		if (reindexActionJob != null)
		{
			Stream<Entity> reindexActions = dataService
					.findAll(REINDEX_ACTION, new QueryImpl<>().eq(REINDEX_ACTION_GROUP, reindexActionJob));
			Map<String, Long> numberOfActionsPerEntity = reindexActions
					.collect(groupingBy(reindexAction -> reindexAction.getString(ENTITY_FULL_NAME), counting()));
			indexStatus.addActionCounts(numberOfActionsPerEntity);

			ReindexJobExecution reindexJobExecution = reindexJobExecutionFactory.create();
			reindexJobExecution.setUser("admin");
			reindexJobExecution.setReindexActionJobID(transactionId);
			ReindexJob job = reindexJobFactory.createJob(reindexJobExecution);
			CompletableFuture.runAsync(job::call, executorService)
					.thenRun(() -> indexStatus.removeActionCounts(numberOfActionsPerEntity));
		}
		else
		{
			LOG.debug("No reindex job found for id [{}].", transactionId);
		}
	}

	@Override
	public void waitForAllIndicesStable() throws InterruptedException
	{
		indexStatus.waitForAllEntitiesToBeStable();
	}

	@Override
	public void waitForIndexToBeStableIncludingReferences(String entityName) throws InterruptedException
	{
		indexStatus.waitForIndexToBeStableIncludingReferences(dataService.getEntityMetaData(entityName));
	}

	/**
	 * Cleans up successful ReindexJobExecutions that finished longer than five minutes ago.
	 */
	@Scheduled(fixedRate = 5 * 60 * 1000)
	public void cleanupJobExecutions()
	{
		runAsSystem(() -> {
			LOG.trace("Clean up Reindex job executions...");
			Date fiveMinutesAgo = from(now().minusMinutes(5).toInstant());
			boolean reindexJobExecutionExists = dataService.hasRepository(REINDEX_JOB_EXECUTION);
			if (reindexJobExecutionExists)
			{
				Stream<Entity> executions = dataService.getRepository(REINDEX_JOB_EXECUTION).query()
						.lt(END_DATE, fiveMinutesAgo).and().eq(STATUS, SUCCESS.toString()).findAll();
				dataService.delete(REINDEX_JOB_EXECUTION, executions);
				LOG.debug("Cleaned up Reindex job executions.");
			}
			else
			{
				LOG.warn(REINDEX_JOB_EXECUTION + " does not exist");
			}
		});
	}

}
