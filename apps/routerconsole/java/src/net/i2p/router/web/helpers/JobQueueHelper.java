package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.i2p.data.DataHelper;
import net.i2p.router.Job;
import net.i2p.router.JobStats;
import net.i2p.router.web.HelperBase;
import net.i2p.util.ObjectCounter;

import net.i2p.util.SystemVersion;

public class JobQueueHelper extends HelperBase {

//    private static final int MAX_JOBS = 50;
    // 32 if android (see below)
    private static int MAX_JOBS = 72;

    public String getJobQueueSummary() {
        try {
            if (_out != null) {
                renderStatusHTML(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderStatusHTML(sw);
                return sw.toString();
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return "";
        }
    }

    /**
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void renderStatusHTML(Writer out) throws IOException {
        List<Job> readyJobs = new ArrayList<Job>(8);
        List<Job> timedJobs = new ArrayList<Job>(128);
        List<Job> activeJobs = new ArrayList<Job>(8);
        List<Job> justFinishedJobs = new ArrayList<Job>(8);

        int numRunners = _context.jobQueue().getJobs(readyJobs, timedJobs, activeJobs, justFinishedJobs);

        StringBuilder buf = new StringBuilder(32*1024);
        buf.append("<div class=\"joblog\">");
        if (isAdvanced()) {
            buf.append("<h2 id=\"jobrunners\">").append(_t("Job runners"))
               .append(": ").append(numRunners).append("</h2>\n");
        }

        long now = _context.clock().now();

        if ((activeJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=\"activejobs\">")
               .append(_t("Active jobs")).append(": ").append(activeJobs.size())
               .append("</h3>\n<ol class=\"jobqueue\">\n");
            for (int i = 0; i < activeJobs.size(); i++) {
                Job j = activeJobs.get(i);
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ");
                buf.append(_t("started {0} ago", DataHelper.formatDuration2(now-j.getTiming().getStartAfter()))).append("</li>\n");
            }
            buf.append("</ol>\n");
        }

        if ((justFinishedJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=\"finishedjobs\">")
               .append(_t("Just finished jobs")).append(": ").append(justFinishedJobs.size())
               .append("</h3>\n<ol class=\"jobqueue\">\n");
            for (int i = 0; i < justFinishedJobs.size(); i++) {
                Job j = justFinishedJobs.get(i);
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ");
                buf.append(_t("finished {0} ago", DataHelper.formatDuration2(now-j.getTiming().getActualEnd())));
                buf.append("</li>\n");
            }
            buf.append("</ol>\n");
        }

        if ((readyJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=\"readyjobs\">")
               .append(_t("Ready/waiting jobs")).append(": ").append(readyJobs.size())
               .append("</h3>\n<ol class=\"jobqueue\">\n");
            ObjectCounter<String> counter = new ObjectCounter<String>();
            for (int i = 0; i < readyJobs.size(); i++) {
                Job j = readyJobs.get(i);
                counter.increment(j.getName());
                if (SystemVersion.isAndroid())
                    MAX_JOBS = 32;
                if (i >= MAX_JOBS)
                    continue;
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ");
                buf.append(_t("waiting")).append(' ').append(DataHelper.formatDuration2(now-j.getTiming().getStartAfter()));
                buf.append("</li>\n");
            }
            buf.append("</ol>\n");
            getJobCounts(buf, counter);
            out.write(buf.toString());
            buf.setLength(0);
        }

        buf.append("<h3 id=\"scheduledjobs\">")
           .append(_t("Scheduled jobs")).append(": ").append(timedJobs.size())
           .append("</h3>\n<ol class=\"jobqueue\">\n");

        ObjectCounter<String> counter = new ObjectCounter<String>();
        getJobCounts(buf, counter);
        out.write(buf.toString());
        buf.setLength(0);

        long prev = Long.MIN_VALUE;
        counter.clear();
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            counter.increment(j.getName());
            if (i >= MAX_JOBS)
                continue;
            long time = j.getTiming().getStartAfter() - now;
            // translators: {0} is a job name, {1} is a time, e.g. 6 min
            buf.append("<li>").append(_t("{0} starts in {1}", "<b title=\"" + j.toString() + "\">" +
                       j.getName() + "</b> &#10140; ", "<i>" + DataHelper.formatDuration2(time) + "</i>"));
            // debug, don't bother translating
            if (time < 0)
                buf.append(" [DELAYED]");
            if (time < prev)
                buf.append(" [OUT OF ORDER]");
            prev = time;
            buf.append("</li>\n");
        }
        buf.append("</ol>\n</div>\n");
        getJobCounts(buf, counter);
        out.write(buf.toString());
        buf.setLength(0);
        getJobStats(buf);
        out.write(buf.toString());
    }

    /** @since 0.9.5 */
    private void getJobCounts(StringBuilder buf, ObjectCounter<String> counter) {
        List<String> names = new ArrayList<String>(counter.objects());
        if (names.size() < 4)
            return;

        buf.append("<table id=\"schedjobs\">\n");
        buf.append("<thead><tr><th>").append(_t("Queue Totals")).append("</th></tr></thead>\n");
            Collections.sort(names, new JobCountComparator(counter));
            buf.append("<tr><td>\n<ul>\n");
            for (String name : names) {
                     buf.append("<li><span class=\"jobcount\"><b>").append(name).append(":</b> ")
                         .append(counter.count(name)).append("</span></li>\n");
                 }
            // TODO: Add aggregate total for single jobs and total for all jobs and only display jobnames with > 1 job
            // buf.append("<span class=\"jobcount\"><b>").append(_t("Otherjobs")).append(":</b> ")
            // .append(otherjobs).append("</span>").append(_t("Total jobs scheduled")).append(": ").append(totaljobs);
            buf.append("</ul></td></tr></table>\n");
    }

    /**
     *  Render the HTML for the job stats.
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void getJobStats(StringBuilder buf) {
        buf.append("<div class=\"widescroll\">")
           .append("<h3 id=\"totaljobstats\">")
           .append(_t("Total Job Statistics"))
           .append("</h3>\n");
        buf.append("<table id=\"jobstats\">\n<tr>\n" +
                   "<th>").append(_t("Job")).append("</th>" +
                   "<th>").append(_t("Runs")).append("</th>" +
                   "<th>").append(_t("Dropped")).append("</th>" +
                   "<th>").append(_t("Time")).append("</th>" +
                   "<th><i>").append(_t("Avg")).append("</i></th>" +
                   "<th><i>").append(_t("Max")).append("</i></th>" +
                   "<th><i>").append(_t("Min")).append("</i></th>");
            if (isAdvanced()) {
                buf.append("<th>").append(_t("Pending")).append("</th>" +
                           "<th><i>").append(_t("Avg")).append("</i></th>" +
                           "<th><i>").append(_t("Max")).append("</i></th>" +
                           "<th><i>").append(_t("Min")).append("</i></th>");
            }
        buf.append("\n</tr>\n");

        long totRuns = 0;
        long totDropped = 0;
        long totExecTime = 0;
        long avgExecTime = 0;
        long maxExecTime = -1;
        long minExecTime = -1;
        long totPendingTime = 0;
        long avgPendingTime = 0;
        long maxPendingTime = -1;
        long minPendingTime = -1;

        List<JobStats> tstats = new ArrayList<JobStats>(_context.jobQueue().getJobStats());
        Collections.sort(tstats, new JobStatsComparator());

        for (JobStats stats : tstats) {
            buf.append("<tr>");
            // TODO: Add tooltip with simpleName to job name
            //buf.append("<td><b title=\"").append(getClass().getSimpleName()).append("\">").append(stats.getName()).append("</b></td>");
            buf.append("<td><b>").append(stats.getName()).append("</b></td>");
            buf.append("<td>").append(stats.getRuns()).append("</td>");
            buf.append("<td>").append(stats.getDropped()).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(stats.getTotalTime())).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(stats.getAvgTime())).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(stats.getMaxTime())).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(stats.getMinTime())).append("</td>");
            if (isAdvanced()) {
                buf.append("<td>").append(DataHelper.formatDuration2(stats.getTotalPendingTime())).append("</td>");
                buf.append("<td>").append(DataHelper.formatDuration2(stats.getAvgPendingTime())).append("</td>");
                buf.append("<td>").append(DataHelper.formatDuration2(stats.getMaxPendingTime())).append("</td>");
                buf.append("<td>").append(DataHelper.formatDuration2(stats.getMinPendingTime())).append("</td>");
            }
            buf.append("</tr>\n");
            totRuns += stats.getRuns();
            totDropped += stats.getDropped();
            totExecTime += stats.getTotalTime();
            if (stats.getMaxTime() > maxExecTime)
                maxExecTime = stats.getMaxTime();
            if ( (minExecTime < 0) || (minExecTime > stats.getMinTime()) )
                minExecTime = stats.getMinTime();
            totPendingTime += stats.getTotalPendingTime();
            if (stats.getMaxPendingTime() > maxPendingTime)
                maxPendingTime = stats.getMaxPendingTime();
            if ( (minPendingTime < 0) || (minPendingTime > stats.getMinPendingTime()) )
                minPendingTime = stats.getMinPendingTime();
        }

        if (totRuns != 0) {
            if (totExecTime != 0)
                avgExecTime = totExecTime / totRuns;
            if (totPendingTime != 0)
                avgPendingTime = totPendingTime / totRuns;
        }

        buf.append("<tr class=\"tablefooter\">");
        buf.append("<td><b>").append(_t("Summary")).append("</b></td>");
        buf.append("<td>").append(totRuns).append("</td>");
        buf.append("<td>").append(totDropped).append("</td>");
        buf.append("<td>").append(DataHelper.formatDuration2(totExecTime)).append("</td>");
        buf.append("<td>").append(DataHelper.formatDuration2(avgExecTime)).append("</td>");
        buf.append("<td>").append(DataHelper.formatDuration2(maxExecTime)).append("</td>");
        buf.append("<td>").append(DataHelper.formatDuration2(minExecTime)).append("</td>");
        if (isAdvanced()) {
            buf.append("<td>").append(DataHelper.formatDuration2(totPendingTime)).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(avgPendingTime)).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(maxPendingTime)).append("</td>");
            buf.append("<td>").append(DataHelper.formatDuration2(minPendingTime)).append("</td>");
        }
        buf.append("</tr></table>\n");
        buf.append("</div>\n");
    }

    /** @since 0.8.9 */
    private static class JobStatsComparator implements Comparator<JobStats>, Serializable {
         public int compare(JobStats l, JobStats r) {
             return l.getName().compareTo(r.getName());
        }
    }

    /** @since 0.9.5 */
    private static class JobCountComparator implements Comparator<String>, Serializable {
         private final ObjectCounter<String> _counter;

         public JobCountComparator(ObjectCounter<String> counter) {
             _counter = counter;
         }

         public int compare(String l, String r) {
             // reverse
             int lc = _counter.count(l);
             int rc = _counter.count(r);
             if (lc > rc)
                 return -1;
             if (lc < rc)
                 return 1;
             return l.compareTo(r);
        }
    }
}
