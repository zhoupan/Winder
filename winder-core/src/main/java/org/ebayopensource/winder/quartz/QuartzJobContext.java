/**
 * Copyright (c) 2016 eBay Software Foundation. All rights reserved.
 *
 * Licensed under the MIT license.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 *
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.ebayopensource.winder.quartz;


import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

import org.ebayopensource.winder.*;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.impl.triggers.AbstractTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.ebayopensource.winder.quartz.QuartzWinderConstants.*;

public class QuartzJobContext implements WinderJobContext {

    private final JobExecutionContext quartzCtx;
    private final QuartzJobDetail jobDetail;
    private final WinderJobSummary summary;
    private final QuartzJobId jobId;
    private Trigger trigger;

    private WinderSchedulerManager scheduler;

    private static Logger log = LoggerFactory.getLogger(QuartzJobContext.class);

    private WinderEngine winderEngine;

    private int initStep = 0;
    private int maxStep = 100000;

    private final JobDataMap map;

    public QuartzJobContext(WinderEngine winderEngine, JobExecutionContext qctx) {
        this.winderEngine = winderEngine;
        this.scheduler = winderEngine.getSchedulerManager();
        quartzCtx = qctx;

        JobDetail jd = quartzCtx.getJobDetail();
        jobId = new QuartzJobId(jd.getKey(), winderEngine.getClusterName());
        jobDetail = new QuartzJobDetail(winderEngine, jobId, jd);
        summary = jobDetail.getSummary();

        trigger = quartzCtx.getTrigger();
        if (trigger == null) {
            throw new IllegalStateException("missing trigger");
        }

        WinderConfiguration configuration = winderEngine.getConfiguration();
        initStep = configuration.getInt("winder_steps_init_step", 0);
        maxStep = configuration.getInt("winder_steps_max_step", 100000);

        map = jobDetail.getJobDataMap();
    }

    public <TI extends TaskInput, TR extends TaskResult>  WinderJobDetail<TI, TR> getJobDetail() {
        return jobDetail;
    }

    @Override
    public WinderEngine getEngine() {
        return winderEngine;
    }

    public QuartzJobId getJobId() {
        return jobId;
    }

    public String getJobIdAsString() {
        return getJobId().toString();
    }

    @Override
    public int getJobStep() {
        String jobStep = map.getString(KEY_JOB_STEP);
        if (jobStep == null) { //For eBay code backward compatible
            jobStep = map.getString(KEY_JOBSTAGE);
        }

        int result = -1;
        if (jobStep != null) {
            try {
                result = Integer.parseInt(jobStep);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad job step " + jobStep, e);
            }
            if (result != -1 && (result < initStep || result > maxStep)) {
                throw new IllegalArgumentException("bad job step " + result);
            }

            if (log.isDebugEnabled()) {
                log.debug(String.format("Job {%s} execution status {%s} step {%s}", getJobId(), getJobStatus(), result));
            }
        }
        return result;
    }

    @Override
    public void setJobStep(int step) {
        map.put(KEY_JOB_STEP, String.valueOf(step));
    }


    public boolean isRecovering() {
        return quartzCtx.isRecovering();
    }

    public String getStatusMessage() {
        String result = jobDetail.getJobDataMap().getString(KEY_JOBSTATUSMSG);
        return (result == null) ? "" : result;
    }

    public void setStatusMessage(String msg) {
        String nmsg = (msg == null) ? "" : msg;
        jobDetail.getJobDataMap().put(KEY_JOBSTATUSMSG, nmsg);
    }


    public void setStatusMessage(String msg, Throwable t) {
        if (t != null) {
            StringWriter writer = new StringWriter();
            if (msg != null) {
                writer.write(msg);
                writer.append("\r\n");
            }
            t.printStackTrace(new PrintWriter(writer));
            setStatusMessage(writer.toString());
        }
        else {
            setStatusMessage(msg);
        }
    }

    public boolean isAwaitingForAction(boolean isAwaiting) {
        return jobDetail.getJobDataMap().getBoolean(KEY_JOB_IS_AWAITING_FOR_ACTION);
    }

    public void setAwaitingForAction(boolean isAwaiting) {
        jobDetail.getJobDataMap().put(KEY_JOB_IS_AWAITING_FOR_ACTION, isAwaiting);
    }

//    public void setComplete() {
//        markJobCompleteAndUnschedule(StatusEnum.COMPLETED);
//    }

    public void setError() {
        done(StatusEnum.ERROR);
    }

//    public void setCompleteWithWarning() {
//        done(StatusEnum.WARNING);
//    }

    public void done(StatusEnum status) {
        if (status != StatusEnum.UNKNOWN) {
            jobDetail.getJobDataMap().put(KEY_JOBSTATUS, String.valueOf(status));
        }
        Date endDate = new Date();
        jobDetail.setEndDate(winderEngine.formatDate(endDate));

        if (log.isDebugEnabled()) {
            log.debug("Job complete: " + jobId.toString());
        }

        //stop the firing
        if (trigger instanceof AbstractTrigger) {
            AbstractTrigger abstractTrigger = (AbstractTrigger) trigger;
            abstractTrigger.setEndTime(endDate);
            abstractTrigger.setNextFireTime(null);
        } else {
            if (log.isErrorEnabled()) {
                log.error("Unexpected trigger type, not AbstractTrigger.");
            }
        }
        try {
            scheduler.unscheduleJob(jobId);
        } catch (WinderException e) {
            log.error("Failure unscheduling job: " + jobId.toString(), e);
        }
    }

    public StatusEnum getJobStatus() {
        String statusName = jobDetail.getJobDataMap().getString(KEY_JOBSTATUS);
        if (statusName == null) {
            return StatusEnum.UNKNOWN;
        }
        StatusEnum result;
        try {
            result = StatusEnum.valueOf(statusName);
        } catch (Exception e) {
            return StatusEnum.UNKNOWN;
        }
        return result;
    }

    public void setJobStatus(StatusEnum status) {
        jobDetail.getJobDataMap().put(KEY_JOBSTATUS,status.name());
    }


    public <TI extends TaskInput, TR extends TaskResult> WinderJobSummary<TI, TR> getJobSummary() {
        return summary;
    }

    @Override
    public StatusUpdate addUpdate(StatusEnum status, String message) {
        return getJobSummary().addUpdate(status, message);
    }

    @Override
    public StatusUpdate addUpdate(StatusEnum status, String message, Throwable cause) {
        return getJobSummary().addUpdate(status, message, cause);
    }

    public JobId[] getChildJobs() {
        return summary.getChildJobIds();
    }

    public void updateJobData() throws WinderException{
        scheduler.updateJobData(jobDetail);
    }
}
