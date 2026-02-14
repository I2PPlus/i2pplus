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
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
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
    private static final long RECENT_WINDOW_MS = 60 * 1000; // 60 seconds

    private String _requestURI;

    /**
     * Set the request URI for parsing query parameters
     * @param uri the full request URI including query string
     * @since 0.9.68+
     */
    public void setRequestURI(String uri) {
        _requestURI = uri;
    }

    /**
     * Check if we should show all-time stats or recent (last minute) stats
     * @return true if showing recent stats only
     * @since 0.9.68+
     */
    private boolean isRecentMode() {
        if (_requestURI == null) return true; // Default to recent
        return !_requestURI.contains("period=all");
    }

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
        long now = _context.clock().now();

        if ((activeJobs.size() != 0) && (isAdvanced())) {
            buf.append("<h3 id=activejobs>")
               .append(_t("Active jobs")).append(": ").append(activeJobs.size())
               .append("</h3>\n<ol class=jobqueue>\n");
            for (int i = 0; i < activeJobs.size(); i++) {
                Job j = activeJobs.get(i);
                long startTime = j.getTiming().getStartAfter();
                long elapsed = Math.max(0, now - startTime); // Prevent negative durations
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ");
                buf.append(_t("started {0} ago", DataHelper.formatDuration2(elapsed))).append("</li>\n");
            }
            buf.append("</ol>\n");
        }

        if ((activeJobs.size() + justFinishedJobs.size() > 0) && (isAdvanced())) {
            int maxRunners = _context.jobQueue().getMaxRunnerCount();
            int activeCount = activeJobs.size() + justFinishedJobs.size();
            buf.append("<h3 id=finishedjobs>")
               .append(_t("Completed jobs")).append(": ").append(justFinishedJobs.size())
               .append(" <span id=jobRunners>").append(_t("Runners")).append(": ")
               .append(_t("Active")).append(": ").append(activeCount)
               .append(" / ").append(_t("Max")).append(": ").append(maxRunners)
               .append("</span>")
               .append("</h3>\n<ol class=jobqueue>\n");

            // Group finished jobs by name and completion time
            Map<String, Map<Long, List<Job>>> groupedFinishedJobs = new HashMap<String, Map<Long, List<Job>>>();

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

            // Use Set to avoid duplicate timestamps
            java.util.Set<Long> uniqueTimes = new java.util.HashSet<Long>();
            for (Map<Long, List<Job>> timeGroups : groupedFinishedJobs.values()) {
                uniqueTimes.addAll(timeGroups.keySet());
            }
            List<Long> allTimes = new ArrayList<Long>(uniqueTimes);
            Collections.sort(allTimes, Collections.reverseOrder());

            int displayedJobCount = 0;
            int maxFinishedJobsDisplayed = 40;

            for (Long completionTime : allTimes) {
                for (String jobName : groupedFinishedJobs.keySet()) {
                    Map<Long, List<Job>> timeGroups = groupedFinishedJobs.get(jobName);
                    if (!timeGroups.containsKey(completionTime)) {
                        continue;
                    }

                    List<Job> jobsAtTime = timeGroups.get(completionTime);

                    if (displayedJobCount + jobsAtTime.size() > maxFinishedJobsDisplayed) {
                        if (displayedJobCount < maxFinishedJobsDisplayed && jobsAtTime.size() == 1) {
                            Job firstJob = jobsAtTime.get(0);
                            long elapsed = Math.max(0, now - completionTime);
                            String timeAgo = DataHelper.formatDuration2(elapsed);
                            String jobDisplay = "<b title=\"" + firstJob.toString() + "\">" + jobName + "</b>";

                            buf.append("<li>").append(jobDisplay).append(" &#10140; ");
                            if (completionTime <= 0 || elapsed == 0) {
                                buf.append(_t("finished just now"));
                            } else {
                                buf.append(_t("finished {0} ago", timeAgo));
                            }
                            buf.append("</li>\n");
                            displayedJobCount++;
                        }
                        continue;
                    }

                    Job firstJob = jobsAtTime.get(0);
                    displayedJobCount += jobsAtTime.size();

                    long elapsed = Math.max(0, now - completionTime);
                    String timeAgo = DataHelper.formatDuration2(elapsed);
                    String jobDisplay = "<b title=\"" + firstJob.toString() + "\">" + jobName + "</b>";

                    if (jobsAtTime.size() > 1) {
                        jobDisplay += " <span class=jobsCounter>" + jobsAtTime.size() + "</span>";
                    }

                    buf.append("<li>").append(jobDisplay).append(" &#10140; ");
                    if (completionTime <= 0 || elapsed == 0) {
                        buf.append(_t("finished just now"));
                    } else {
                        buf.append(_t("finished {0} ago", timeAgo));
                    }
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
            for (int i = 0; i < readyJobs.size() && i < MAX_JOBS_DISPLAYED; i++) { // Early break
                Job j = readyJobs.get(i);
                readyJobsCounter.increment(j.getName());
                long elapsed = Math.max(0, now - j.getTiming().getStartAfter());
                buf.append("<li><b title=\"").append(j.toString()).append("\">").append(j.getName()).append("</b> &#10140; ")
                   .append(_t("waiting")).append(' ').append(DataHelper.formatDuration2(elapsed))
                   .append("</li>\n");
            }
            buf.append("</ol>\n");
            getJobCounts(buf, readyJobsCounter);
            out.append(buf);
            buf.setLength(0);
        }

        ObjectCounterUnsafe<String> totalQueueCounter = new ObjectCounterUnsafe<String>();
        Map<String, Map<Long, List<Job>>> groupedJobs = new HashMap<String, Map<Long, List<Job>>>();
        int eligibleScheduledCount = 0;

        // First pass: collect and group all jobs
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            String jobName = j.getName();
            long delay = j.getTiming().getStartAfter() - now;
            boolean isDisabled = jobName.toLowerCase(java.util.Locale.US).contains("disabled");

            // Count eligible jobs (1s to 30s delay, not disabled)
            if (delay > 1000 && delay <= 30000 && !isDisabled) {
                totalQueueCounter.increment(jobName);
                eligibleScheduledCount++;
            }

            // Skip for display: non-positive, disabled, <1s, or >30s
            if (delay <= 0 || isDisabled || delay < 1000 || delay > 30000) {
                continue;
            }

            // Group by job name and rounded time (seconds)
            long timeInSeconds = (delay / 1000) * 1000;
            if (!groupedJobs.containsKey(jobName)) {
                groupedJobs.put(jobName, new HashMap<Long, List<Job>>());
            }
            Map<Long, List<Job>> timeGroups = groupedJobs.get(jobName);
            if (!timeGroups.containsKey(timeInSeconds)) {
                timeGroups.put(timeInSeconds, new ArrayList<Job>());
            }
            timeGroups.get(timeInSeconds).add(j);
        }

        // Build header AFTER computing counts
        int displayedJobCount = 0;
        int displayedLiCount = 0;

        // Sort jobs for display
        List<JobTimeEntry> sortedJobs = new ArrayList<JobTimeEntry>();
        for (String jobName : groupedJobs.keySet()) {
            Map<Long, List<Job>> timeGroups = groupedJobs.get(jobName);
            for (Long time : timeGroups.keySet()) {
                sortedJobs.add(new JobTimeEntry(jobName, time, timeGroups.get(time)));
            }
        }
        Collections.sort(sortedJobs);

        // Build scheduled jobs list
        StringBuilder scheduledBuf = new StringBuilder(8192);
        scheduledBuf.append("<h3 id=scheduledjobs>")
           .append(_t("Scheduled jobs")).append(": ")
           .append(displayedJobCount).append(" / ").append(eligibleScheduledCount) // Will update later
           .append("</h3>\n<ol class=jobqueue>\n");

        for (JobTimeEntry entry : sortedJobs) {
            if (displayedLiCount >= MAX_JOBS_DISPLAYED) break;

            List<Job> jobsAtTime = entry.jobs;
            String jobName = entry.jobName;
            Job firstJob = jobsAtTime.get(0);

            // Find earliest actual start time in group
            long earliestDelay = Long.MAX_VALUE;
            for (Job j : jobsAtTime) {
                long jobDelay = j.getTiming().getStartAfter() - now;
                if (jobDelay < earliestDelay) earliestDelay = jobDelay;
            }
            earliestDelay = Math.max(1, earliestDelay); // Prevent zero/negative

            displayedLiCount++;
            displayedJobCount += jobsAtTime.size();

            String timeStr = "<i>" + DataHelper.formatDuration2(earliestDelay) + "</i>";
            String jobDisplay = "<b title=\"" + firstJob.toString() + "\">" + jobName + "</b>";
            if (jobsAtTime.size() > 1) {
                jobDisplay += " <span class=jobsCounter>" + jobsAtTime.size() + "</span>";
            }

            scheduledBuf.append("<li>")
               .append(_t("{0} starting in {1}", jobDisplay + " &#10140; ", timeStr))
               .append("</li>\n");
        }
        scheduledBuf.append("</ol>\n</div>\n");

        // Update header with actual counts (simple string replacement safe here since we control the format)
        String headerPlaceholder = displayedJobCount + " / " + eligibleScheduledCount;
        scheduledBuf.replace(0, scheduledBuf.indexOf("</h3>") + 5,
            "<h3 id=scheduledjobs>" + _t("Scheduled jobs") + ": " + headerPlaceholder + "</h3>\n");

        if (eligibleScheduledCount <= 0) {
            buf.setLength(0);
            return;
        } else {
            buf.append(scheduledBuf);
            getJobCounts(buf, totalQueueCounter);
            out.append(buf);
            buf.setLength(0);
        }
    }

    private void renderJobStatsHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(32*1024);
        getJobStats(buf);
        out.append(buf);
        buf.setLength(0);
    }

    /** @since 0.9.5 */
    private void getJobCounts(StringBuilder buf, ObjectCounterUnsafe<String> counter) {
        List<String> names = new ArrayList<>(counter.objects());

        buf.append("<table id=schedjobs>\n")
           .append("<thead><tr><th>").append(_t("Queue Totals")).append("</th></tr></thead>\n");
        Collections.sort(names, new JobCountComparator(counter));
        buf.append("<tr><td>\n<ul>\n");

        final String TEST_TUNNEL_EN = "Test Local Tunnel";
        int maxTestJobs = TestJob.maxQueuedTests;

        for (String name : names) {
            buf.append("<li><span class=jobcount><b>").append(name).append("</b> <span class=jobsCounter>");
            // Only special-case the English job name (job names aren't translated)
            if (TEST_TUNNEL_EN.equals(name)) {
                buf.append(counter.count(name)).append(" / ").append(maxTestJobs);
            } else {
                buf.append(counter.count(name));
            }
            buf.append("</span></span></li>\n");
        }
        buf.append("</ul></td></tr></table>\n");
    }

    /**
     *  Render the HTML for the job stats.
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void getJobStats(StringBuilder buf) {
        boolean recentMode = isRecentMode();
        long now = System.currentTimeMillis();
        long cutoff = now - RECENT_WINDOW_MS;

        buf.append("<div class=widescroll>\n");

        // First pass: collect job stats and calculate max execution time
        long totRuns = 0;
        long totDropped = 0;
        double totExecTime = 0.0;
        double totPendingTime = 0.0;
        double maxExecTime = 0.0;
        double minExecTime = Double.MAX_VALUE;
        double maxPendingTime = 0.0;
        double minPendingTime = Double.MAX_VALUE;
        StringBuilder rowsBuf = new StringBuilder(32*1024);

        List<JobStats> tstats = new ArrayList<JobStats>(_context.jobQueue().getJobStats());
        Collections.sort(tstats, new JobStatsComparator());

        for (JobStats stats : tstats) {
            // Get recent stats for this job
            JobStats.RecentStats recent = stats.getRecentStats();

            // In recent mode, skip jobs with no recent executions
            if (recentMode && recent.runs == 0) continue;

            // Skip single-run jobs and disabled jobs BEFORE accumulating totals
            if (stats.getRuns() < 2) continue;
            if (stats.getName().contains("(disabled)")) continue;

            // Use recent stats for display and totals when in recent mode
            long displayRuns = recentMode ? recent.runs : stats.getRuns();
            long displayDropped = recentMode ? 0 : stats.getDropped(); // No recent dropped count available

            // Get double-precision timing values (in milliseconds)
            double displayTotalTime = recentMode ? recent.getTotalTime() : stats.getTotalTimeDouble();
            double displayTotalPending = recentMode ? recent.getTotalPendingTime() : stats.getTotalPendingTimeDouble();
            double displayMaxTime = recentMode ? recent.getMaxTime() : stats.getMaxTimeDouble();
            double displayMinTime = recentMode ? recent.getMinTime() : stats.getMinTimeDouble();
            double displayMaxPending = recentMode ? recent.getMaxPendingTime() : stats.getMaxPendingTimeDouble();
            double displayMinPending = recentMode ? recent.getMinPendingTime() : stats.getMinPendingTimeDouble();
            double displayAvgTime = recentMode ? recent.getAvgTime() : stats.getAvgTime();
            double displayAvgPending = recentMode ? recent.getAvgPendingTime() : stats.getAvgPendingTime();

            totRuns += displayRuns;
            totDropped += displayDropped;
            totExecTime += displayTotalTime;
            totPendingTime += displayTotalPending;

            boolean isRunningSlow = displayRuns > 3 && displayAvgTime > 1000.0;

            // Use microsecond/millisecond formatting for sub-millisecond precision display
            rowsBuf.append("<tr")
               .append(isRunningSlow ? " class=slowAvg" : "")
               .append("><td class=jobname><b>")
               .append(stats.getName())
               .append("</b></td><td class=totalRuns><span>")
               .append(displayRuns)
               .append("</span></td><td class=totalDropped><span>")
               .append(displayDropped)
               .append("</span></td><td class=totalRunTime data-sort=")
               .append((long) (displayTotalTime * 1000.0)) // sort by microseconds
               .append("><span>")
               .append(DataHelper.formatDuration2(displayTotalTime))
               .append("</span></td><td class=avgRunTime data-sort=")
               .append((long) (displayAvgTime * 1000.0))
               .append("><span>")
               .append(DataHelper.formatDuration2(displayAvgTime))
               .append("</span></td><td class=maxRunTime data-sort=")
               .append((long) (displayMaxTime * 1000.0))
               .append("><span>")
               .append(DataHelper.formatDuration2(displayMaxTime))
               .append("</span></td><td class=minRunTime data-sort=")
               .append((long) (displayMinTime * 1000.0))
               .append("><span>")
               .append(DataHelper.formatDuration2(displayMinTime))
               .append("</span></td>");
            if (isAdvanced()) {
                rowsBuf.append("<td class=totalPendingTime data-sort=")
                   .append((long) (displayTotalPending * 1000.0))
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayTotalPending))
                   .append("</span></td><td class=avgPendingTime data-sort=")
                   .append((long) (displayAvgPending * 1000.0))
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayAvgPending))
                   .append("</span></td><td class=maxPendingTime data-sort=")
                   .append((long) (displayMaxPending * 1000.0))
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayMaxPending))
                   .append("</span></td><td class=minPendingTime data-sort=")
                   .append((long) (displayMinPending * 1000.0))
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayMinPending))
                   .append("</span></td>");
            }
            rowsBuf.append("</tr>\n");

            // Update min/max AFTER filtering
            if (displayMaxTime > maxExecTime) maxExecTime = displayMaxTime;
            if (displayMinTime >= 0 && displayMinTime < minExecTime) minExecTime = displayMinTime;
            if (displayMaxPending > maxPendingTime) maxPendingTime = displayMaxPending;
            if (displayMinPending >= 0 && displayMinPending < minPendingTime) minPendingTime = displayMinPending;
        }

        // Handle edge case: no valid jobs displayed
        double avgExecTime = totRuns > 0 ? totExecTime / totRuns : 0.0;
        double avgPendingTime = totRuns > 0 ? totPendingTime / totRuns : 0.0;
        if (minExecTime == Double.MAX_VALUE) minExecTime = 0.0;
        if (minPendingTime == Double.MAX_VALUE) minPendingTime = 0.0;

        // Get AVG lag from RateStat (same as sidebar) - historical average
        double avgLag = 0.0;
        RateStat rs = _context.statManager().getRate("jobQueue.jobLag");
        if (rs != null) {
            Rate lagRate = rs.getRate(RateConstants.ONE_MINUTE);
            if (lagRate != null) {
                avgLag = lagRate.getAverageValue();
            }
        }
        // Get peak lag from completed jobs (worst delay experienced)
        long peakLag = _context.jobQueue().getPeakLag();

        // Render header with calculated values
        buf.append("<h3 id=totaljobstats>")
           .append(_t("Job Statistics"))
           .append(recentMode ? " (" + _t("last minute") + ")" : "")
           .append("<span id=lag style=float:right>").append(_t("Job Lag"))
           .append(": ").append(_t("AVG")).append(":<span style=text-transform:lowercase;letter-spacing:0> ")
           .append(DataHelper.formatDuration2(avgLag))
           .append("</span> / ").append(_t("PEAK")).append(":<span style=text-transform:lowercase;letter-spacing:0> ")
           .append(DataHelper.formatDuration2(peakLag))
           .append("</span></span><span id=toggleJobstats>");

        // Add toggle link
        if (recentMode) {
            buf.append(" <a href=\"/jobs?period=all\">").append(_t("All Stats")).append("</a>");
        } else {
            buf.append(" <a href=\"/jobs?period=recent\">").append(_t("Last 60s")).append("</a>");
        }

        buf.append("</span></h3>\n")
           .append("<table id=jobstats>\n<thead><tr><th class=jobname data-sort-default data-sort-direction=ascending>")
           .append(_t("Job"))
           .append("</th><th class=totalRuns data-sort-method=number>")
           .append(_t("Runs"))
           .append("</th><th class=totalDropped data-sort-method=number>")
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
        buf.append("</tr></thead>\n<tbody id=statCount>\n")
           .append(rowsBuf)
           .append("</tbody>\n<tfoot id=statTotals><tr class=tablefooter data-sort-method=none><td><b>")
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
