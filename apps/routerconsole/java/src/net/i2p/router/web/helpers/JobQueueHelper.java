package net.i2p.router.web.helpers;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringWriter;
import java.io.Writer;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.i2p.data.DataHelper;
import net.i2p.router.Job;
import net.i2p.router.JobStats;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.router.web.HelperBase;
import net.i2p.util.ObjectCounterUnsafe;
import net.i2p.util.SystemVersion;

/**
 * Helper for job queue page rendering and form processing.
 * @since 0.9.33
 */
public class JobQueueHelper extends HelperBase {

    private static int CORES = SystemVersion.getCores();
    private static boolean isSlow = SystemVersion.isSlow();
    private static int MAX_JOBS_DISPLAYED = 30;

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

    public String getJobQueueStats() {
        try {
            if (_out != null) {
                renderJobStatsHTML(_out);
                return "";
            } else {
                StringWriter sw = new StringWriter(32*1024);
                renderJobStatsHTML(sw);
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
        buf.append("<div class=joblog>");
        if (isAdvanced()) {
            buf.append("<h2 id=jobrunners>").append(_t("Job runners"))
               .append(": ").append(numRunners).append("</h2>\n");
        }

        long now = _context.clock().now();

        if ((activeJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=activejobs>")
               .append(_t("Active jobs")).append(": ").append(activeJobs.size())
               .append("</h3>\n<ol class=jobqueue>\n");
            for (int i = 0; i < activeJobs.size(); i++) {
                Job j = activeJobs.get(i);
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ");
                buf.append(_t("started {0} ago", DataHelper.formatDuration2(now-j.getTiming().getStartAfter()))).append("</li>\n");
            }
            buf.append("</ol>\n");
        }

        if ((justFinishedJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=finishedjobs>")
               .append(_t("Just finished jobs")).append(": ").append(justFinishedJobs.size())
               .append("</h3>\n<ol class=jobqueue>\n");

            // Group finished jobs by name and completion time
            Map<String, Map<Long, List<Job>>> groupedFinishedJobs = new HashMap<String, Map<Long, List<Job>>>();

            // Group by job name, then by completion time
            for (int i = 0; i < justFinishedJobs.size(); i++) {
                Job j = justFinishedJobs.get(i);
                String jobName = j.getName();
                long completionTime = j.getTiming().getActualEnd();

                if (!groupedFinishedJobs.containsKey(jobName)) {
                    groupedFinishedJobs.put(jobName, new HashMap<Long, List<Job>>());
                }
                Map<Long, List<Job>> timeGroups = groupedFinishedJobs.get(jobName);
                if (!timeGroups.containsKey(completionTime)) {
                    timeGroups.put(completionTime, new ArrayList<Job>());
                }
                timeGroups.get(completionTime).add(j);
            }

            // Sort completion times in descending order (newest first)
            List<Long> allTimes = new ArrayList<Long>();
            for (Map<Long, List<Job>> timeGroups : groupedFinishedJobs.values()) {
                allTimes.addAll(timeGroups.keySet());
            }
            Collections.sort(allTimes, Collections.reverseOrder());

            // Display grouped jobs, newest first, with max 40 total jobs
            int displayedJobCount = 0;
            int maxFinishedJobsDisplayed = 40;

            for (Long completionTime : allTimes) {
                for (String jobName : groupedFinishedJobs.keySet()) {
                    Map<Long, List<Job>> timeGroups = groupedFinishedJobs.get(jobName);
                    if (!timeGroups.containsKey(completionTime)) {
                        continue;
                    }

                    List<Job> jobsAtTime = timeGroups.get(completionTime);

                    // Check if adding this group would exceed the limit
                    if (displayedJobCount + jobsAtTime.size() > maxFinishedJobsDisplayed) {
                        // If this single job fits, display it and break
                        if (displayedJobCount < maxFinishedJobsDisplayed && jobsAtTime.size() == 1) {
                            Job firstJob = jobsAtTime.get(0);
                            String timeAgo = completionTime > 0 ? DataHelper.formatDuration2(now - completionTime) : "";
                            String jobDisplay = "<b title=\"" + firstJob.toString() + "\">" + jobName + "</b>";
                            boolean finishedNow = completionTime <= 0;

                            buf.append("<li>").append(jobDisplay).append(" &#10140; ");
                            if (finishedNow) {buf.append(_t("finished just now"));}
                            else {buf.append(_t("finished {0} ago", timeAgo));}
                            buf.append("</li>\n");
                            displayedJobCount++;
                        }
                        continue;
                    }

                    Job firstJob = jobsAtTime.get(0);
                    displayedJobCount += jobsAtTime.size();

                    String timeAgo = completionTime > 0 ? DataHelper.formatDuration2(now - completionTime) : "";
                    String jobDisplay = "<b title=\"" + firstJob.toString() + "\">" + jobName + "</b>";
                    boolean finishedNow = completionTime <= 0;

                    if (jobsAtTime.size() > 1) {
                        jobDisplay += " <span class=jobsCounter>" + jobsAtTime.size() + "</span>";
                    }

                    buf.append("<li>").append(jobDisplay).append(" &#10140; ");
                    if (finishedNow) {buf.append(_t("finished just now"));}
                    else {buf.append(_t("finished {0} ago", timeAgo));}
                    buf.append("</li>\n");

                    if (displayedJobCount >= maxFinishedJobsDisplayed) {
                        break;
                    }
                }
                if (displayedJobCount >= maxFinishedJobsDisplayed) {
                    break;
                }
            }
            buf.append("</ol>\n");
        }

        if ((readyJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=readyjobs>")
               .append(_t("Ready/waiting jobs")).append(": ").append(readyJobs.size())
               .append("</h3>\n<ol class=jobqueue>\n");
            ObjectCounterUnsafe<String> readyJobsCounter = new ObjectCounterUnsafe<String>();
            for (int i = 0; i < readyJobs.size(); i++) {
                Job j = readyJobs.get(i);
                readyJobsCounter.increment(j.getName());
                if (i >= MAX_JOBS_DISPLAYED) {continue;}
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ")
                   .append(_t("waiting")).append(' ').append(DataHelper.formatDuration2(now-j.getTiming().getStartAfter()))
                   .append("</li>\n");
            }
            buf.append("</ol>\n");
            getJobCounts(buf, readyJobsCounter);
            out.append(buf);
            buf.setLength(0);
        }

        ObjectCounterUnsafe<String> displayedJobsCounter = new ObjectCounterUnsafe<String>();
        ObjectCounterUnsafe<String> totalQueueCounter = new ObjectCounterUnsafe<String>();

        // Group jobs by name and start time
        Map<String, Map<Long, List<Job>>> groupedJobs = new HashMap<String, Map<Long, List<Job>>>();

        int eligibleScheduledCount = 0;

        // First pass: collect and group all jobs
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            String jobName = j.getName();
            long time = j.getTiming().getStartAfter() - now;

            // Count all eligible scheduled jobs for queue totals (excluding disabled jobs, > 1 min, and < 1 sec)
            if (time > 0 && time <= 30000 && time >= 1000 && !jobName.toLowerCase().contains("disabled")) {
                totalQueueCounter.increment(jobName);
                eligibleScheduledCount++;
            }

            // Skip jobs with non-positive start times, disabled jobs, jobs > 1 minute, and jobs < 1 second
            if (time <= 0 || jobName.toLowerCase().contains("disabled") || time > 30000 || time < 1000) {
                continue;
            }

            // Group by job name, then by start time in seconds (for display grouping)
            long timeInSeconds = (time / 1000) * 1000; // Round down to nearest second
            if (!groupedJobs.containsKey(jobName)) {
                groupedJobs.put(jobName, new HashMap<Long, List<Job>>());
            }
            Map<Long, List<Job>> timeGroups = groupedJobs.get(jobName);
            if (!timeGroups.containsKey(timeInSeconds)) {
                timeGroups.put(timeInSeconds, new ArrayList<Job>());
            }
            timeGroups.get(timeInSeconds).add(j);
        }

        // Header will be updated after display loop to show actual displayed count
        buf.append("<h3 id=scheduledjobs>")
           .append(_t("Scheduled jobs")).append(": ")
           .append("0") // temporary placeholder
           .append("</h3>\n<ol class=jobqueue>\n");

        // Second pass: display grouped jobs
        int displayedLiCount = 0;
        int displayedJobCount = 0;

        // Sort all jobs by time (lowest delay first) for display
        List<JobTimeEntry> sortedJobs = new ArrayList<JobTimeEntry>();
        for (String jobName : groupedJobs.keySet()) {
            Map<Long, List<Job>> timeGroups = groupedJobs.get(jobName);
            for (Long time : timeGroups.keySet()) {
                List<Job> jobsAtTime = timeGroups.get(time);
                sortedJobs.add(new JobTimeEntry(jobName, time, jobsAtTime));
            }
        }
        Collections.sort(sortedJobs);

        for (JobTimeEntry entry : sortedJobs) {
            if (displayedLiCount >= MAX_JOBS_DISPLAYED) {break;}

            List<Job> jobsAtTime = entry.jobs;
            String jobName = entry.jobName;
            long time = entry.time;
            Job firstJob = jobsAtTime.get(0);

            displayedJobsCounter.increment(jobName);
            displayedLiCount++;
            displayedJobCount += jobsAtTime.size();

            // Use the actual earliest start time from the grouped jobs for more accurate display
            long earliestTime = Long.MAX_VALUE;
            for (Job j : jobsAtTime) {
                long jobTime = j.getTiming().getStartAfter() - now;
                if (jobTime < earliestTime) {
                    earliestTime = jobTime;
                }
            }

            String timeStr = "<i>" + DataHelper.formatDuration2(earliestTime) + "</i>";
            String jobDisplay = "<b title=\"" + firstJob.toString() + "\">" + jobName + "</b>";

            if (jobsAtTime.size() > 1) {
                jobDisplay += " <span class=jobsCounter>" + jobsAtTime.size() + "</span>";
            }

            buf.append("<li>") // translators: {0} is a job name, {1} is a time, e.g. 6 min
               .append(_t("{0} starting in {1}", jobDisplay + " &#10140; ", timeStr)).append("</li>\n");
            if (displayedLiCount >= MAX_JOBS_DISPLAYED) {break;}
        }
        buf.append("</ol>\n</div>\n");

        // Update header with actual displayed job count
        String content = buf.toString();
        String headerText = displayedJobCount + " / " + eligibleScheduledCount;
        String jobsHeaderText = _t("Scheduled jobs");
        content = content.replaceFirst("<h3 id=scheduledjobs>" + jobsHeaderText + ": 0",
                                     "<h3 id=scheduledjobs>" + jobsHeaderText + ": " + headerText);
        buf.setLength(0);
        buf.append(content);

        getJobCounts(buf, totalQueueCounter);
        out.append(buf);
        buf.setLength(0);
    }

    private void renderJobStatsHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(32*1024);
        getJobStats(buf);
        out.append(buf);
        buf.setLength(0);
    }

    /** @since 0.9.5 */
    private void getJobCounts(StringBuilder buf, ObjectCounterUnsafe<String> counter) {
        List<String> names = new ArrayList<String>(counter.objects());
        if (names.size() < 4) {return;}

        buf.append("<table id=schedjobs>\n");
        buf.append("<thead><tr><th>").append(_t("Queue Totals")).append("</th></tr></thead>\n");
        Collections.sort(names, new JobCountComparator(counter));
        buf.append("<tr><td>\n<ul>\n");
        int maxTestJobs = TestJob.maxQueuedTests;

        for (String name : names) {
            buf.append("<li><span class=jobcount><b>").append(name).append("</b> <span class=jobsCounter>")
               .append(name.equals(_t("Test Local Tunnel")) ? counter.count(name) + " / " + maxTestJobs : counter.count(name))
               .append("</span></span></li>\n");
        }
        buf.append("</ul></td></tr></table>\n");
    }

    /**
     *  Render the HTML for the job stats.
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void getJobStats(StringBuilder buf) {
        buf.append("<div class=widescroll>\n<h3 id=totaljobstats>")
           .append(_t("Job Statistics (excluding single-shot jobs)"))
           .append("</h3>\n<table id=jobstats>\n<thead><tr><th class=jobname data-sort-default data-sort-direction=ascending>")
           .append(_t("Job"))
           .append("</th><th class=totalRuns data-sort-method=number>")
           .append(_t("Runs"))
           .append("</th><th class=totalDropped class=dropped data-sort-method=number>")
           .append(_t("Dropped"))
           .append("</th><th class=totalRunTime data-sort-method=number>")
           .append(_t("Time"))
           .append("</th><th class=avgRunTime data-sort-method=number>")
           .append(_t("Avg"))
           .append("</th><th class=maxRunTime data-sort-method=number>")
           .append(_t("Max"))
           .append("</th><th class=minRunTime data-sort-method=number>")
           .append(_t("Min"))
           .append("</th>");
        if (isAdvanced()) {
            buf.append("<th class=totalPendingTime data-sort-method=number>").append(_t("Pending")).append("</th>")
               .append("<th class=avgPendingTime data-sort-method=number>").append(_t("Avg")).append("</th>")
               .append("<th class=maxPendingTime data-sort-method=number>").append(_t("Max")).append("</th>")
               .append("<th class=minPendingTime data-sort-method=number>").append(_t("Min")).append("</th>");
        }
        buf.append("</tr></thead>\n<tbody id=statCount>\n");

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
            totDropped += stats.getDropped();
            totExecTime += stats.getTotalTime();
            totPendingTime += stats.getTotalPendingTime();
            totRuns += stats.getRuns();
            boolean isRunningSlow = stats.getRuns() > 3 && stats.getAvgTime() > 1000;

            if (stats.getRuns() < 2) {
                totRuns -=1;
                totExecTime -= stats.getTotalTime();
                totPendingTime -= stats.getTotalPendingTime();
                continue;
            }
            if (stats.getName().contains("(disabled)")) {
                totRuns -=1;
                continue;
            }
            buf.append("<tr")
               .append(isRunningSlow ? "class=slowAvg" : "")
               .append("><td class=jobname><b>")
               .append(stats.getName())
               .append("</b></td><td class=totalRuns><span>")
               .append(stats.getRuns())
               .append("</span></td><td class=totalDropped><span>")
               .append(stats.getDropped())
               .append("</span></td><td class=totalRunTime data-sort=")
               .append(stats.getTotalTime())
               .append("><span>")
               .append(DataHelper.formatDuration2(stats.getTotalTime()))
               .append("</span></td><td class=avgRunTime data-sort=")
               .append(stats.getAvgTime())
               .append("><span>")
               .append(DataHelper.formatDuration2(stats.getAvgTime()))
               .append("</span></td><td class=maxRunTime data-sort=")
               .append(stats.getMaxTime())
               .append("><span>")
               .append(DataHelper.formatDuration2(stats.getMaxTime()))
               .append("</span></td><td class=minRunTime data-sort=")
               .append(stats.getMinTime())
               .append("><span>")
               .append(DataHelper.formatDuration2(stats.getMinTime()))
               .append("</span></td>");
            if (isAdvanced()) {
                buf.append("<td class=totalPendingTime data-sort=")
                   .append(stats.getTotalPendingTime())
                   .append("><span>")
                   .append(DataHelper.formatDuration2(stats.getTotalPendingTime()))
                   .append("</span></td><td class=avgPendingTime data-sort=")
                   .append(stats.getAvgPendingTime())
                   .append("><span>")
                   .append(DataHelper.formatDuration2(stats.getAvgPendingTime()))
                   .append("</span></td><td class=maxPendingTime data-sort=")
                   .append(stats.getMaxPendingTime())
                   .append("><span>")
                   .append(DataHelper.formatDuration2(stats.getMaxPendingTime()))
                   .append("</span></td><td class=minPendingTime data-sort=")
                   .append(stats.getMinPendingTime())
                   .append("><span>")
                   .append(DataHelper.formatDuration2(stats.getMinPendingTime()))
                   .append("</span></td>");
            }
            buf.append("</tr>\n");
            if (stats.getMaxTime() > maxExecTime)
                maxExecTime = stats.getMaxTime();
            if ( (minExecTime < 0) || (minExecTime > stats.getMinTime()) )
                minExecTime = stats.getMinTime();
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

        buf.append("</tbody>\n<tfoot id=statTotals><tr class=tablefooter data-sort-method=none><td><b>")
           .append(_t("Summary"))
           .append("</b></td><td>")
           .append(totRuns)
           .append("</td><td>")
           .append(totDropped)
           .append("</td><td>")
           .append(DataHelper.formatDuration2(totExecTime))
           .append("</td><td>")
           .append(DataHelper.formatDuration2(avgExecTime))
           .append("</td><td>")
           .append(DataHelper.formatDuration2(maxExecTime))
           .append("</td><td>")
           .append(DataHelper.formatDuration2(minExecTime))
           .append("</td>");
        if (isAdvanced()) {
            buf.append("<td>")
               .append(DataHelper.formatDuration2(totPendingTime))
               .append("</td><td>")
               .append(DataHelper.formatDuration2(avgPendingTime))
               .append("</td><td>")
               .append(DataHelper.formatDuration2(maxPendingTime))
               .append("</td><td>")
               .append(DataHelper.formatDuration2(minPendingTime))
               .append("</td>");
        }
        buf.append("</tr></tfoot>\n</table>\n</div>\n");
    }

    /** @since 0.8.9 */
    private static class JobStatsComparator implements Comparator<JobStats>, Serializable {
         private final Collator coll = Collator.getInstance();

         public int compare(JobStats l, JobStats r) {
             return coll.compare(l.getName(), r.getName());
        }
    }

    /** @since 0.9.5 */
    private static class JobCountComparator implements Comparator<String>, Serializable {
        private final transient ObjectCounterUnsafe<String> _counter;
        private final Collator coll = Collator.getInstance();

        public JobCountComparator(ObjectCounterUnsafe<String> counter) {
             _counter = counter;
        }

        public int compare(String l, String r) {
            // Sort alphabetically by job name
            return coll.compare(l, r);
        }
    }

    /** Helper class for sorting jobs by time */
    private static class JobTimeEntry implements Comparable<JobTimeEntry>, Serializable {
        final String jobName;
        final long time;
        final List<Job> jobs;

        JobTimeEntry(String jobName, long time, List<Job> jobs) {
            this.jobName = jobName;
            this.time = time;
            this.jobs = jobs;
        }

        public int compareTo(JobTimeEntry other) {
            // Sort by time (lowest delay first)
            if (this.time < other.time) return -1;
            if (this.time > other.time) return 1;
            // If same time, sort by job name
            return Collator.getInstance().compare(this.jobName, other.jobName);
        }
    }

}
