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
import java.util.Locale;
import java.util.Map;
import net.i2p.data.DataHelper;
import net.i2p.router.Job;
import net.i2p.router.JobStats;
import net.i2p.router.tunnel.pool.TestJob;
import net.i2p.router.web.HelperBase;
import net.i2p.stat.Rate;
import net.i2p.stat.RateConstants;
import net.i2p.stat.RateStat;
import net.i2p.util.Log;
import net.i2p.util.ObjectCounterUnsafe;

/**
 * Helper for job queue page rendering and form processing.
 * @since 0.9.33
 */
public class JobQueueHelper extends HelperBase {

    private static int MAX_JOBS_DISPLAYED = 30;
    private static final long RECENT_WINDOW_MS = (long) 10 * 1000;

    private String _requestURI;

    /**
     * Set the request URI.
     *
     * @param uri the request URI
     */
    public void setRequestURI(String uri) {
        _requestURI = uri;
    }

    private boolean isRecentMode() {
        if (_requestURI == null) return true;
        return !_requestURI.contains("period=all");
    }

    /**
     * Get the job queue summary HTML.
     *
     * @return the HTML summary
     */
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
            _log.error("Error rendering job queue summary", ioe);
            return "";
        }
    }

    /**
     * Get the job queue stats HTML.
     *
     * @return the HTML stats
     */
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
            _log.error("Error rendering job queue stats", ioe);
            return "";
        }
    }

    /**
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void renderStatusHTML(Writer out) throws IOException {
        List<Job> readyJobs = new ArrayList<>(8);
        List<Job> timedJobs = new ArrayList<>(128);
        List<Job> activeJobs = new ArrayList<>(8);
        List<Job> justFinishedJobs = new ArrayList<>(8);

        _context.jobQueue().getJobs(readyJobs, timedJobs, activeJobs, justFinishedJobs);

        // Get dropped count once for both ready and scheduled sections
        int droppedCount = _context.jobQueue().getAndResetDroppedCount();

        StringBuilder buf = new StringBuilder(32*1024);
        buf.append("<div class=joblog>");
        long now = _context.clock().now();
        boolean inactive = activeJobs.size() <= 0;

        long maxLag = _context.jobQueue().getMaxLag();
        String lagStr = "";
        // Show lag - always check rate stat like SidebarHelper
        RateStat rs = _context.statManager().getRate("jobQueue.jobLag");
        if (rs != null) {
            Rate lagRate = rs.getRate(RateConstants.ONE_MINUTE);
            double avgLag = lagRate.getAverageValue();
            if (maxLag > 0) {
                lagStr = " <span id=maxLag class=jobCounter style=float:right>" +
                         _t("Delayed: {0}", DataHelper.formatDuration2(maxLag)) + "</span>";
            } else if (avgLag > 0) {
                if (avgLag < 0.001) {
                    // Under 1ms - show in microseconds
                    lagStr = " <span id=avgLag class=jobCounter style=float:right>" +
                             _t("Average: {0}µs", String.format("%.0f", avgLag * 1000)) + "</span>";
                } else {
                    lagStr = " <span id=avgLag class=jobCounter style=float:right>" +
                             _t("Average: {0}", DataHelper.formatDuration2((long)(avgLag * 1000))) + "</span>";
                }
            }
        }
        buf.append("<div class=tablewrap id=active>\n<h3 id=activejobs")
           .append(inactive ? " class=nojobs" : "").append(">")
           .append(_t("Active jobs")).append(": ").append(activeJobs.size())
           .append(lagStr)
           .append("</h3>\n");

        if (!activeJobs.isEmpty()) {
            buf.append("<ol class=jobqueue>\n");
            // Group active jobs by name
            Map<String, List<Job>> groupedActiveJobs = new HashMap<>();
            for (int i = 0; i < activeJobs.size(); i++) {
                Job j = activeJobs.get(i);
                String jobName = j.getName();
                if (!groupedActiveJobs.containsKey(jobName)) {
                    groupedActiveJobs.put(jobName, new ArrayList<>());
                }
                groupedActiveJobs.get(jobName).add(j);
            }
            // Sort and display
            List<String> sortedNames = new ArrayList<>(groupedActiveJobs.keySet());
            Collections.sort(sortedNames);
            for (String jobName : sortedNames) {
                List<Job> jobs = groupedActiveJobs.get(jobName);
                buf.append("<li>").append(jobNameDisplay(jobName, jobs)).append("</li>\n");
            }
            buf.append("</ol>");
        }
        buf.append("</div>\n");

        if ((!justFinishedJobs.isEmpty()) && (isAdvanced())) {
            // Calculate total runtime for just finished jobs
            long totalRuntime = 0;
            for (Job j : justFinishedJobs) {
                long start = j.getTiming().getActualStart();
                long end = j.getTiming().getActualEnd();
                if (start > 0 && end > 0) {
                    totalRuntime += (end - start);
                }
            }
            String runtimeStr = " <span id=totalRuntime class=jobCounter style=float:right>" +
                                _t("Duration: {0}", DataHelper.formatDuration2(totalRuntime)) +
                                "</span>";
            buf.append("<div class=tablewrap id=finished>\n<h3 id=finishedjobs>")
               .append(_t("Just finished jobs")).append(": ").append(justFinishedJobs.size()).append(runtimeStr)
               .append("</h3>\n<ol class=jobqueue>\n");

            // Group finished jobs by name and completion time, most recent first
            List<JobGroup> finishedGroups = groupByNameAndTime(justFinishedJobs, j -> j.getTiming().getActualEnd());
            Collections.sort(finishedGroups, new JobGroupTimeDescComparator());

            int displayedJobCount = 0;
            for (JobGroup group : finishedGroups) {
                if (displayedJobCount >= MAX_JOBS_DISPLAYED) {
                    break;
                }
                displayedJobCount += group.jobs.size();

                long elapsed = Math.max(0, now - group.time);
                String timeAgo = DataHelper.formatDuration2(elapsed);
                buf.append("<li>").append(jobNameDisplay(group.jobName, group.jobs)).append(" &#10140; ");
                if (group.time <= 0 || elapsed == 0) {
                    buf.append(_t("finished just now"));
                } else {
                    buf.append(_t("finished {0} ago", timeAgo));
                }
                buf.append("</li>\n");
            }
            buf.append("</ol></div>\n");
        }

        boolean hasJobs = !readyJobs.isEmpty();
        String droppedStr = " <span id=dropped class=jobCounter style=float:right>" + _t("Dropped: {0}", droppedCount) + "</span>";
        buf.append("<div class=tablewrap id=ready>\n<h3 id=readyjobs")
           .append(!hasJobs ? " class=nojobs" : "").append(">")
           .append(_t("Ready / waiting jobs")).append(": ").append(readyJobs.size())
           .append(droppedStr)
           .append("</h3>\n");
        if (hasJobs) {
            buf.append("<ol class=jobqueue>\n");

            // Group ready jobs by name and elapsed time (rounded to nearest second)
            List<JobGroup> readyGroups = groupByNameAndTime(readyJobs, j -> {
                long elapsed = Math.max(0, now - j.getTiming().getStartAfter());
                return (elapsed / 1000) * 1000; // Round to nearest second
            });
            Collections.sort(readyGroups, new JobGroupNameTimeComparator());

            int displayedJobCount = 0;
            for (JobGroup group : readyGroups) {
                if (displayedJobCount >= MAX_JOBS_DISPLAYED) {
                    break;
                }
                displayedJobCount += group.jobs.size();

                String timeStr = "<i>" + DataHelper.formatDuration2(group.time) + "</i>";
                buf.append("<li>").append(jobNameDisplay(group.jobName, group.jobs));
                if (group.time > 0) {
                    buf.append(" &#10140; ").append(_t("waiting {0}", timeStr));
                }
                buf.append("</li>\n");
            }
            buf.append("</ol>");
        }
        buf.append("</div>\n");
        out.append(buf);
        buf.setLength(0);

        ObjectCounterUnsafe<String> totalQueueCounter = new ObjectCounterUnsafe<>();
        List<Job> scheduledJobs = new ArrayList<>(timedJobs.size());
        int eligibleScheduledCount = 0;
        long maxScheduledDelay = 0;

        // First pass: count eligible jobs and track the longest delay
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            String jobName = j.getName();
            long delay = j.getTiming().getStartAfter() - now;
            boolean isDisabled = jobName.toLowerCase(Locale.US).contains("disabled");

            // Track max scheduled delay
            if (delay > maxScheduledDelay) {
                maxScheduledDelay = delay;
            }

            // Count eligible jobs (1s to 20s delay, not disabled)
            if (delay > 1000 && delay <= 20000 && !isDisabled) {
                totalQueueCounter.increment(jobName);
                eligibleScheduledCount++;
            }

            // Skip for display: non-positive, disabled, <1s, or >20s
            if (delay <= 0 || isDisabled || delay < 1000 || delay > 20000) {
                continue;
            }
            scheduledJobs.add(j);
        }

        if (eligibleScheduledCount <= 0) {
            buf.append("</div>");
            out.append(buf);
            return;
        }

        // Group the display-eligible jobs by name and rounded delay (seconds), soonest first
        List<JobGroup> scheduledGroups = groupByNameAndTime(scheduledJobs,
            j -> (j.getTiming().getStartAfter() - now) / 1000 * 1000);
        Collections.sort(scheduledGroups);

        String maxDelayStr = " <span id=longest class=jobCounter style=float:right>" +
                             _t("Max wait: {0}", DataHelper.formatDuration2(maxScheduledDelay)) + "</span>";
        StringBuilder scheduledBuf = new StringBuilder(8192);
        scheduledBuf.append("<ol class=jobqueue>\n");
        int displayedJobCount = 0;
        for (JobGroup group : scheduledGroups) {
            if (displayedJobCount >= MAX_JOBS_DISPLAYED) {
                break;
            }
            List<Job> jobsAtTime = group.jobs;
            displayedJobCount += jobsAtTime.size();

            // Find earliest actual start time in group
            long earliestDelay = Long.MAX_VALUE;
            for (Job j : jobsAtTime) {
                long jobDelay = j.getTiming().getStartAfter() - now;
                if (jobDelay < earliestDelay) {
                    earliestDelay = jobDelay;
                }
            }
            earliestDelay = Math.max(1, earliestDelay); // Prevent zero/negative

            String timeStr = "<i>" + DataHelper.formatDuration2(earliestDelay) + "</i>";
            String jobWithArrow = jobNameDisplay(group.jobName, jobsAtTime) + " &#10140; ";
            scheduledBuf.append("<li>")
               .append(_t("{0} starting in {1}", jobWithArrow, timeStr))
               .append("</li>\n");
        }
        scheduledBuf.append("</ol>\n</div>\n");

        // Header counts are only known after the rows above
        buf.append("<div class=tablewrap id=scheduled>\n<h3 id=scheduledjobs>")
           .append(_t("Scheduled jobs")).append(": ")
           .append(displayedJobCount).append(" / ").append(eligibleScheduledCount)
           .append(maxDelayStr)
           .append("</h3>\n")
           .append(scheduledBuf);
        getJobCounts(buf, totalQueueCounter, eligibleScheduledCount);
        buf.append("</div>");
        out.append(buf);
    }

    /**
     * Group the jobs by name, then by the time key derived from each job.
     *
     * @param jobs the jobs to group
     * @param timeFn derives the time key for each job
     * @return the groups, in no particular order - sort before display
     * @since 0.9.70+
     */
    private static List<JobGroup> groupByNameAndTime(List<Job> jobs, JobTimeFn timeFn) {
        List<JobGroup> groups = new ArrayList<>(jobs.size());
        Map<String, Map<Long, List<Job>>> grouped = new HashMap<>();
        for (Job j : jobs) {
            String jobName = j.getName();
            long timeKey = timeFn.timeFor(j);
            Map<Long, List<Job>> timeGroups = grouped.get(jobName);
            if (timeGroups == null) {
                timeGroups = new HashMap<>();
                grouped.put(jobName, timeGroups);
            }
            List<Job> bucket = timeGroups.get(timeKey);
            if (bucket == null) {
                bucket = new ArrayList<>();
                timeGroups.put(timeKey, bucket);
            }
            bucket.add(j);
        }
        for (Map.Entry<String, Map<Long, List<Job>>> entry : grouped.entrySet()) {
            for (Map.Entry<Long, List<Job>> timeEntry : entry.getValue().entrySet()) {
                groups.add(new JobGroup(entry.getKey(), timeEntry.getKey(), timeEntry.getValue()));
            }
        }
        return groups;
    }

    /**
     * Build the job name label, with a count badge when the group holds
     * several jobs.
     *
     * @param jobName the job name
     * @param jobs the jobs in the group
     * @return the HTML label
     * @since 0.9.70+
     */
    private static String jobNameDisplay(String jobName, List<Job> jobs) {
        StringBuilder buf = new StringBuilder(128);
        buf.append("<b title=\"").append(jobs.get(0).toString()).append("\">").append(jobName).append("</b>");
        if (jobs.size() > 1) {
            buf.append(" <span class=jobsCounter>").append(jobs.size()).append("</span>");
        }
        return buf.toString();
    }

    /**
     * Derives the time key used to group a job.
     *
     * @since 0.9.70+
     */
    private interface JobTimeFn {
        /**
         * The time key for the job.
         *
         * @param job the job
         * @return the time key
         * @since 0.9.70+
         */
        long timeFor(Job job);
    }

    private void renderJobStatsHTML(Writer out) throws IOException {
        StringBuilder buf = new StringBuilder(32*1024);
        getJobStats(buf);
        out.append(buf);
        buf.setLength(0);
    }

    /** @since 0.9.5 */
    private void getJobCounts(StringBuilder buf, ObjectCounterUnsafe<String> counter, int scheduledCount) {
        List<String> names = new ArrayList<>(counter.objects());
        int totalJobs = _context.jobQueue().getReadyCount() + scheduledCount;
        int activeRunners = _context.jobQueue().getActiveRunnerCount();
        int maxRunners = _context.jobQueue().getMaxRunnerCount();
        String runnerStr = " <span id=runners class=jobCounter style=float:right>" + _t("Runners: {0} / {1}", activeRunners, maxRunners) + "</span>";

        buf.append("<div class=tablewrap id=totals>\n<h3 id=qtotals>").append(_t("Queue Totals"))
           .append(": ").append(totalJobs).append(runnerStr).append("</h3><table id=schedjobs>\n<tr><td>\n<ul>\n");

        final String TEST_TUNNEL_EN = "Test Local Tunnel";
        int maxTestJobs = TestJob.maxQueuedTests;
        Collections.sort(names, new JobCountComparator());

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
        buf.append("</ul></td></tr></table>\n</div>\n");
    }

    /**
     *  Render the HTML for the job stats.
     *  Moved from JobQueue
     *  @since 0.8.9
     */
    private void getJobStats(StringBuilder buf) {
        boolean recentMode = isRecentMode();
        if (recentMode) {
            JobStats.enableRecentTracking();
        }
        long now = System.currentTimeMillis();
        long cutoff = now - RECENT_WINDOW_MS;

        buf.append("<div class=widescroll>\n");
        buf.append("<h3 id=totaljobstats>")
           .append(_t("Job Statistics"))
           .append(recentMode ? " (" + _t("last 10s") + ")" : "")
           .append("<span id=toggleJobstats>");

        if (recentMode) {
            buf.append(" <a href=\"/jobs?period=all\">").append(_t("All Stats")).append("</a>");
        } else {
            buf.append(" <a href=\"/jobs?period=recent\">").append(_t("Last 10s")).append("</a>");
        }

        buf.append("</span></h3>\n")
           .append("<table id=jobstats")
           .append(isAdvanced() ? " class=advmode" : "")
           .append(">\n<thead><tr><th class=jobname data-sort-default data-sort-direction=ascending>")
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
        buf.append("</tr></thead>\n<tbody id=statCount>\n");

        long totRuns = 0;
        long totDropped = 0;
        long totExecTime = 0;
        long totPendingTime = 0;
        long maxExecTime = 0;
        long minExecTime = Long.MAX_VALUE;
        long maxPendingTime = 0;
        long minPendingTime = Long.MAX_VALUE;

        List<JobStats> tstats = new ArrayList<>(_context.jobQueue().getJobStats());
        Collections.sort(tstats, new JobStatsComparator());

        for (JobStats stats : tstats) {
            JobStats.RecentStats recent = stats.getRecentStats();

            if (recentMode && recent.runs == 0) continue;

            if ((recentMode ? recent.runs : stats.getRuns()) < 2 && stats.getDropped() < 1) continue;
            if (stats.getName().contains("(disabled)")) continue;

            long displayRuns = recentMode ? recent.runs : stats.getRuns();
            // If we're in recent mode and hit the buffer limit, indicate with +
            if (recentMode && recent.runs >= stats.getMaxRecentEntries()) {
                displayRuns = stats.getMaxRecentEntries();
            }
            long displayDropped = recentMode ? 0 : stats.getDropped();
            long displayTotalTime = recentMode ? recent.totalTime : stats.getTotalTime();
            long displayTotalPending = recentMode ? recent.totalPendingTime : stats.getTotalPendingTime();
            long displayMaxTime = recentMode ? recent.maxTime : stats.getMaxTime();
            long displayMinTime = recentMode ? recent.minTime : stats.getMinTime();
            long displayMaxPending = recentMode ? recent.maxPendingTime : stats.getMaxPendingTime();
            long displayMinPending = recentMode ? recent.minPendingTime : stats.getMinPendingTime();
            double displayAvgTime = recentMode ? recent.getAvgTime() : stats.getAvgTime();
            double displayAvgPending = recentMode ? recent.getAvgPendingTime() : stats.getAvgPendingTime();

            totRuns += displayRuns;
            totDropped += displayDropped;
            totExecTime += displayTotalTime;
            totPendingTime += displayTotalPending;

            boolean isRunningSlow = displayRuns > 3 && displayAvgTime > 1000;

            // Add + indicator if we've hit the buffer limit in recent mode
            String runsDisplay = Long.toString(displayRuns);
            if (recentMode && recent.runs >= stats.getMaxRecentEntries()) {
                runsDisplay = displayRuns + "+";
            }

            buf.append("<tr")
               .append(isRunningSlow ? " class=slowAvg" : "")
               .append("><td class=jobname><b>")
               .append(stats.getName())
               .append("</b></td><td class=totalRuns><span>")
               .append(runsDisplay)
               .append("</span></td><td class=totalDropped><span>")
               .append(displayDropped)
               .append("</span></td><td class=totalRunTime data-sort=")
               .append(displayTotalTime)
               .append("><span>")
               .append(DataHelper.formatDuration2(displayTotalTime))
               .append("</span></td><td class=avgRunTime data-sort=")
               .append((long) displayAvgTime)
               .append("><span>")
               .append(DataHelper.formatDuration2((long) displayAvgTime))
               .append("</span></td><td class=maxRunTime data-sort=")
               .append(displayMaxTime)
               .append("><span>")
               .append(DataHelper.formatDuration2(displayMaxTime))
               .append("</span></td><td class=minRunTime data-sort=")
               .append(displayMinTime)
               .append("><span>")
               .append(DataHelper.formatDuration2(displayMinTime))
               .append("</span></td>");
            if (isAdvanced()) {
                buf.append("<td class=totalPendingTime data-sort=")
                   .append(displayTotalPending)
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayTotalPending))
                   .append("</span></td><td class=avgPendingTime data-sort=")
                   .append((long) displayAvgPending)
                   .append("><span>")
                   .append(DataHelper.formatDuration2((long) displayAvgPending))
                   .append("</span></td><td class=maxPendingTime data-sort=")
                   .append(displayMaxPending)
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayMaxPending))
                   .append("</span></td><td class=minPendingTime data-sort=")
                   .append(displayMinPending)
                   .append("><span>")
                   .append(DataHelper.formatDuration2(displayMinPending))
                   .append("</span></td>");
            }
            buf.append("</tr>\n");

            if (displayMaxTime > maxExecTime) maxExecTime = displayMaxTime;
            if (displayMinTime >= 0 && displayMinTime < minExecTime) minExecTime = displayMinTime;
            if (displayMaxPending > maxPendingTime) maxPendingTime = displayMaxPending;
            if (displayMinPending >= 0 && displayMinPending < minPendingTime) minPendingTime = displayMinPending;
        }

        long avgExecTime = totRuns > 0 ? totExecTime / totRuns : 0;
        long avgPendingTime = totRuns > 0 ? totPendingTime / totRuns : 0;
        if (minExecTime == Long.MAX_VALUE) minExecTime = 0;
        if (minPendingTime == Long.MAX_VALUE) minPendingTime = 0;

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

        @Override
        public int compare(JobStats l, JobStats r) {
            return coll.compare(l.getName(), r.getName());
        }
    }

    /** @since 0.9.5 */
    private static class JobCountComparator implements Comparator<String>, Serializable {
        private final Collator coll = Collator.getInstance();

        @Override
        public int compare(String l, String r) {
            // Sort alphabetically by job name
            return coll.compare(l, r);
        }
    }

    /**
     * A group of jobs with the same name and time key.
     * Sorts by time, then by name.
     *
     * @since 0.9.70+
     */
    private static class JobGroup implements Comparable<JobGroup>, Serializable {
        final String jobName;
        final long time;
        final List<Job> jobs;
        private static final Collator coll = Collator.getInstance();

        JobGroup(String jobName, long time, List<Job> jobs) {
            this.jobName = jobName;
            this.time = time;
            this.jobs = jobs;
        }

        @Override
        public int compareTo(JobGroup other) {
            // Sort by time (lowest delay first)
            if (this.time < other.time) {
                return -1;
            }
            if (this.time > other.time) {
                return 1;
            }
            // If same time, sort by job name
            return coll.compare(this.jobName, other.jobName);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (!(o instanceof JobGroup)) return false;
            JobGroup other = (JobGroup) o;
            return this.time == other.time && coll.compare(this.jobName, other.jobName) == 0;
        }

        @Override
        public int hashCode() {
            return (int) (time ^ (time >>> 32)) ^ jobName.hashCode();
        }
    }

    /**
     * Sorts job groups by name, then by time.
     *
     * @since 0.9.70+
     */
    private static class JobGroupNameTimeComparator implements Comparator<JobGroup>, Serializable {
        @Override
        public int compare(JobGroup l, JobGroup r) {
            int c = l.jobName.compareTo(r.jobName);
            if (c != 0) {
                return c;
            }
            return Long.compare(l.time, r.time);
        }
    }

    /**
     * Sorts job groups by time descending, keeping equal times in their
     * current order.
     *
     * @since 0.9.70+
     */
    private static class JobGroupTimeDescComparator implements Comparator<JobGroup>, Serializable {
        @Override
        public int compare(JobGroup l, JobGroup r) {
            return Long.compare(r.time, l.time);
        }
    }

}
