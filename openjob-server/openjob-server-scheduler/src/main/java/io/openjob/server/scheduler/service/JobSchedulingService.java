package io.openjob.server.scheduler.service;

import io.openjob.common.constant.CommonConstant;
import io.openjob.common.constant.FailStatusEnum;
import io.openjob.common.constant.InstanceStatusEnum;
import io.openjob.common.constant.TimeExpressionTypeEnum;
import io.openjob.common.request.ServerCheckTaskMasterRequest;
import io.openjob.common.response.WorkerResponse;
import io.openjob.common.util.DateUtil;
import io.openjob.common.util.FutureUtil;
import io.openjob.server.common.ClusterContext;
import io.openjob.server.common.cron.CronExpression;
import io.openjob.server.common.util.ServerUtil;
import io.openjob.server.repository.constant.JobStatusEnum;
import io.openjob.server.repository.dao.JobDAO;
import io.openjob.server.repository.dao.JobInstanceDAO;
import io.openjob.server.repository.entity.Job;
import io.openjob.server.repository.entity.JobInstance;
import io.openjob.server.scheduler.autoconfigure.SchedulerProperties;
import io.openjob.server.scheduler.constant.SchedulerConstant;
import io.openjob.server.scheduler.dto.JobExecuteRequestDTO;
import io.openjob.server.scheduler.dto.JobExecuteResponseDTO;
import io.openjob.server.scheduler.timer.AbstractTimerTask;
import io.openjob.server.scheduler.timer.SchedulerTimerTask;
import io.openjob.server.scheduler.wheel.SchedulerWheel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author stelin swoft@qq.com
 * @since 1.0.0
 */
@Service
@Slf4j
public class JobSchedulingService {
    private final JobDAO jobDAO;
    private final JobInstanceDAO jobInstanceDAO;
    private final SchedulerWheel schedulerWheel;
    private final SchedulerProperties schedulerProperties;


    @Autowired
    public JobSchedulingService(JobDAO jobDAO, JobInstanceDAO jobInstanceDAO, SchedulerWheel schedulerWheel, SchedulerProperties schedulerProperties) {
        this.jobDAO = jobDAO;
        this.jobInstanceDAO = jobInstanceDAO;
        this.schedulerWheel = schedulerWheel;
        this.schedulerProperties = schedulerProperties;
    }

    /**
     * Schedule job.
     */
    public void scheduleJob() {
        List<Long> currentSlots = new ArrayList<>(ClusterContext.getCurrentSlots());
        // Cron jobs.
        this.scheduleCronJob(currentSlots);
    }

    /**
     * Schedule failover job.
     */
    public void scheduleFailoverJob() {
        Set<JobInstance> dispatchList = new HashSet<>();
        Set<Long> currentSlots = ClusterContext.getCurrentSlots();

        // Retry dispatch list.
        long executeTime = DateUtil.timestamp() - this.schedulerProperties.getInstanceFailPeriodTime();
        List<JobInstance> unDispatchList = this.jobInstanceDAO.getUnDispatchedList(currentSlots, executeTime, InstanceStatusEnum.WAITING);
        if (!CollectionUtils.isEmpty(unDispatchList)) {
            dispatchList.addAll(unDispatchList);
            log.info("Retry dispatch list!{}", unDispatchList.stream().map(JobInstance::getId).collect(Collectors.toList()));
        }

        // Fail over list.
        // Must exclude one time job.
        long failoverReportTime = DateUtil.timestamp() - this.schedulerProperties.getInstanceFailPeriodTime();
        List<JobInstance> failoverList = this.jobInstanceDAO.getFailoverList(currentSlots, failoverReportTime, InstanceStatusEnum.RUNNING);
        if (!CollectionUtils.isEmpty(failoverList)) {
            dispatchList.addAll(failoverList);
            log.info("Retry failover list!{}", failoverList.stream().map(JobInstance::getId).collect(Collectors.toList()));
        }

        List<AbstractTimerTask> timerTasks = new ArrayList<>();
        dispatchList.forEach(js -> {
            // Check worker instance.
            if (InstanceStatusEnum.isRunning(js.getStatus()) && this.checkTaskMaster(js)) {
                return;
            }

            // Add time wheel.
            timerTasks.add(this.convertToTimerTask(js));
        });

        // Batch add time task to timing wheel.
        this.schedulerWheel.addTimerTask(timerTasks);
    }

    private Boolean checkTaskMaster(JobInstance js) {
        try {
            ServerCheckTaskMasterRequest checkRequest = new ServerCheckTaskMasterRequest();
            checkRequest.setJobInstanceId(js.getId());
            checkRequest.setJobId(js.getJobId());
            FutureUtil.mustAsk(ServerUtil.getWorkerTaskMasterActor(js.getWorkerAddress()), checkRequest, WorkerResponse.class, 3000L);
            return true;
        } catch (Throwable e) {
            log.info("Check worker failed! workerAddress={}", js.getWorkerAddress());
            return false;
        }
    }

    /**
     * Schedule cron job.
     *
     * @param currentSlots currentSlots
     */
    @Transactional(rollbackFor = Exception.class)
    public void scheduleCronJob(List<Long> currentSlots) {
        long maxExecuteTime = DateUtil.timestamp() + (SchedulerConstant.JOB_FIXED_DELAY / 1000L);
        List<Job> jobs = jobDAO.listScheduledJobs(currentSlots, maxExecuteTime);

        // Create job instance.
        this.createJobInstance(jobs);

        // Update job next execute time.
        jobs.forEach(j -> {
            // Cron job
            if (TimeExpressionTypeEnum.isCron(j.getTimeExpressionType())) {
                try {
                    calculateCronTimeExpression(j);
                } catch (ParseException parseException) {
                    log.error("Cron expression({}) is invalid!", j.getTimeExpression());
                }
            }

            // One time
            if (TimeExpressionTypeEnum.isOneTime(j.getTimeExpressionType())) {
                this.jobDAO.updateByStatusOrDeleted(j.getId(), JobStatusEnum.STOP.getStatus(), null, null);
            }
        });
    }

    /**
     * Execute job
     *
     * @param executeRequestDTO executeRequestDTO
     * @return JobExecuteResponseDTO
     */
    public JobExecuteResponseDTO execute(JobExecuteRequestDTO executeRequestDTO) {
        Job job = this.jobDAO.getById(executeRequestDTO.getId());
        if (Objects.isNull(job)) {
            throw new RuntimeException("Job is not exist!");
        }

        job.setParams(executeRequestDTO.getParams());
        job.setExtendParams(executeRequestDTO.getExtendParams());
        job.setNextExecuteTime(DateUtil.timestamp());
        this.createJobInstance(Collections.singletonList(job));
        return new JobExecuteResponseDTO();
    }

    /**
     * Create job instance.
     *
     * @param jobs jobs
     */
    private void createJobInstance(List<Job> jobs) {
        List<AbstractTimerTask> timerTasks = new ArrayList<>();

        jobs.forEach(j -> {
            long now = DateUtil.timestamp();
            JobInstance jobInstance = new JobInstance();
            jobInstance.setJobId(j.getId());
            jobInstance.setAppId(j.getAppId());
            jobInstance.setNamespaceId(j.getNamespaceId());
            jobInstance.setParamsType(j.getParamsType());
            jobInstance.setParams(j.getParams());
            jobInstance.setSlotsId(j.getSlotsId());
            jobInstance.setDeleteTime(0L);
            jobInstance.setDeleted(CommonConstant.NO);
            jobInstance.setCreateTime(now);
            jobInstance.setUpdateTime(now);
            jobInstance.setStatus(InstanceStatusEnum.WAITING.getStatus());
            jobInstance.setFailStatus(FailStatusEnum.NONE.getStatus());
            jobInstance.setCompleteTime(0L);
            jobInstance.setLastReportTime(0L);
            jobInstance.setProcessorType(j.getProcessorType());
            jobInstance.setProcessorInfo(j.getProcessorInfo());
            jobInstance.setExecuteType(j.getExecuteType());
            jobInstance.setFailRetryInterval(j.getFailRetryInterval());
            jobInstance.setFailRetryTimes(j.getFailRetryTimes());
            jobInstance.setTimeExpressionType(j.getTimeExpressionType());
            jobInstance.setTimeExpression(j.getTimeExpression());
            jobInstance.setExecuteStrategy(j.getExecuteStrategy());
            jobInstance.setExecuteTimeout(j.getExecuteTimeout());
            jobInstance.setConcurrency(j.getConcurrency());
            jobInstance.setWorkerAddress("");
            jobInstance.setExtendParamsType(j.getExtendParamsType());
            jobInstance.setExtendParams(j.getExtendParams());
            jobInstance.setWorkflowId(j.getWorkflowId());
            jobInstance.setExecuteTime(j.getNextExecuteTime());

            Long instanceId = jobInstanceDAO.save(jobInstance);
            jobInstance.setId(instanceId);

            timerTasks.add(this.convertToTimerTask(jobInstance));
        });

        this.schedulerWheel.addTimerTask(timerTasks);
    }

    private AbstractTimerTask convertToTimerTask(JobInstance js) {
        SchedulerTimerTask schedulerTask = new SchedulerTimerTask(js.getId(), js.getSlotsId(), js.getExecuteTime());
        schedulerTask.setJobId(js.getJobId());
        schedulerTask.setJobParamType(js.getParamsType());
        schedulerTask.setJobParams(js.getParams());
        schedulerTask.setJobExtendParamsType(js.getExtendParamsType());
        schedulerTask.setJobExtendParams(js.getExtendParams());
        schedulerTask.setAppid(js.getAppId());
        schedulerTask.setWorkflowId(0L);
        schedulerTask.setProcessorInfo(js.getProcessorInfo());
        schedulerTask.setProcessorType(js.getProcessorType());
        schedulerTask.setExecuteType(js.getExecuteType());
        schedulerTask.setFailRetryTimes(js.getFailRetryTimes());
        schedulerTask.setFailRetryInterval(js.getFailRetryInterval());
        schedulerTask.setExecuteTimeout(js.getExecuteTimeout());
        schedulerTask.setConcurrency(js.getConcurrency());
        schedulerTask.setTimeExpressionType(js.getTimeExpressionType());
        schedulerTask.setTimeExpression(js.getTimeExpression());
        schedulerTask.setExecuteStrategy(js.getExecuteStrategy());
        return schedulerTask;
    }

    private Long calculateNextExecuteTime(Job job, Long now) throws ParseException {
        // Cron type job.
        if (TimeExpressionTypeEnum.CRON_TYPES.contains(job.getTimeExpressionType())) {
            CronExpression cronExpression = new CronExpression(job.getTimeExpression());
            long afterTime = job.getNextExecuteTime();
            if (afterTime < now) {
                afterTime = now;
            }
            Date date = new Date(afterTime * 1000L);
            return cronExpression.getNextValidTimeAfter(date).toInstant().getEpochSecond();
        }

        // Fixed rate job.
        return job.getNextExecuteTime() + Long.parseLong(job.getTimeExpression());
    }

    private void calculateCronTimeExpression(Job j) throws ParseException {
        Long now = DateUtil.timestamp();

        // Calculate next execute time.
        long nextExecuteTime = this.calculateNextExecuteTime(j, now);

        // Update next execute time.
        j.setNextExecuteTime(nextExecuteTime);
        j.setUpdateTime(now);

        if (nextExecuteTime < now + (SchedulerConstant.JOB_FIXED_DELAY / SchedulerConstant.UNIT_MS)) {
            this.createJobInstance(Collections.singletonList(j));

            // Update next execute time.
            j.setNextExecuteTime(this.calculateNextExecuteTime(j, nextExecuteTime));
        }

        // Update
        jobDAO.updateNextExecuteTime(j.getId(), j.getNextExecuteTime(), j.getUpdateTime());
    }
}
