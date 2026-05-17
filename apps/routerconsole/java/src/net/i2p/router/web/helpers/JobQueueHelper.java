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
    private static final long RECENT_WINDOW_MS = 10 * 1000;
    private static final int MAX_RECENT_DISPLAY = 10000;

    private String _requestURI;

    public void setRequestURI(String uri) {
        _requestURI = uri;
    }

    private boolean isRecentMode() {
        if (_requestURI == null) return true;
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
        int totalTimedJobs = timedJobs.size();

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

        if (activeJobs.size() > 0) {
            buf.append("<ol class=jobqueue>\n");
            // Group active jobs by name
            Map<String, List<Job>> groupedActiveJobs = new HashMap<String, List<Job>>();
            for (int i = 0; i < activeJobs.size(); i++) {
                Job j = activeJobs.get(i);
                String jobName = j.getName();
                if (!groupedActiveJobs.containsKey(jobName)) {
                    groupedActiveJobs.put(jobName, new ArrayList<Job>());
                }
                groupedActiveJobs.get(jobName).add(j);
            }
            // Sort and display
            List<String> sortedNames = new ArrayList<>(groupedActiveJobs.keySet());
            Collections.sort(sortedNames);
            for (String jobName : sortedNames) {
                List<Job> jobs = groupedActiveJobs.get(jobName);
                String jobDisplay = "<b title=\"" + jobs.get(0).toString() + "\">" + jobName + "</b>";
                if (jobs.size() > 1) {
                    jobDisplay += " <span class=jobsCounter>" + jobs.size() + "</span>";
                }
                buf.append("<li>").append(jobDisplay).append("</li>\n");
            }
            buf.append("</ol>");
        }
        buf.append("</div>\n");

        if ((justFinishedJobs.size() != 0) && (isAdvanced())) {
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
                for (Map.Entry<String, Map<Long, List<Job>>> entry : groupedFinishedJobs.entrySet()) {
                    String jobName = entry.getKey();
                    Map<Long, List<Job>> timeGroups = entry.getValue();
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
            buf.append("</ol></div>\n");
        }

        boolean hasJobs = readyJobs.size() > 0;
        String droppedStr = " <span id=dropped class=jobCounter style=float:right>" + _t("Dropped: {0}", droppedCount) + "</span>";
        buf.append("<div class=tablewrap id=ready>\n<h3 id=readyjobs")
           .append(!hasJobs ? " class=nojobs" : "").append(">")
           .append(_t("Ready / waiting jobs")).append(": ").append(readyJobs.size())
           .append(droppedStr)
           .append("</h3>\n");
        if (hasJobs) {
            buf.append("<ol class=jobqueue>\n");

            // Group ready jobs by name and elapsed time (rounded to nearest second)
            Map<String, Map<Long, List<Job>>> groupedReadyJobs = new HashMap<String, Map<Long, List<Job>>>();
            for (int i = 0; i < readyJobs.size(); i++) {
                Job j = readyJobs.get(i);
                String jobName = j.getName();
                long elapsed = Math.max(0, now - j.getTiming().getStartAfter());
                long elapsedSeconds = (elapsed / 1000) * 1000; // Round to nearest second
                if (!groupedReadyJobs.containsKey(jobName)) {
                    groupedReadyJobs.put(jobName, new HashMap<Long, List<Job>>());
                }
                Map<Long, List<Job>> timeGroups = groupedReadyJobs.get(jobName);
                if (!timeGroups.containsKey(elapsedSeconds)) {
                    timeGroups.put(elapsedSeconds, new ArrayList<Job>());
                }
                timeGroups.get(elapsedSeconds).add(j);
            }

            // Sort and display
            List<String> sortedJobNames = new ArrayList<>(groupedReadyJobs.keySet());
            Collections.sort(sortedJobNames);
            int displayedJobCount = 0;
            for (String jobName : sortedJobNames) {
                Map<Long, List<Job>> timeGroups = groupedReadyJobs.get(jobName);
                List<Long> sortedTimes = new ArrayList<>(timeGroups.keySet());
                Collections.sort(sortedTimes);

                for (Long elapsedSeconds : sortedTimes) {
                    if (displayedJobCount >= MAX_JOBS_DISPLAYED) break;
                    List<Job> jobsAtTime = timeGroups.get(elapsedSeconds);
                    displayedJobCount += jobsAtTime.size();

                    String timeStr = "<i>" + DataHelper.formatDuration2(elapsedSeconds) + "</i>";
                    String jobDisplay = "<b title=\"" + jobsAtTime.get(0).toString() + "\">" + jobName + "</b>";
                    if (jobsAtTime.size() > 1) {
                        jobDisplay += " <span class=jobsCounter>" + jobsAtTime.size() + "</span>";
                    }

                    buf.append("<li>").append(jobDisplay);
                    if (elapsedSeconds > 0) {
                       buf.append(" &#10140; ").append(_t("waiting {0}", timeStr));
                    }
                    buf.append("</li>\n");
                }
                if (displayedJobCount >= MAX_JOBS_DISPLAYED) break;
            }
            buf.append("</ol>");
        }
        buf.append("</div>\n");
        out.append(buf);
        buf.setLength(0);

        ObjectCounterUnsafe<String> totalQueueCounter = new ObjectCounterUnsafe<String>();
        Map<String, Map<Long, List<Job>>> groupedJobs = new HashMap<String, Map<Long, List<Job>>>();
        int eligibleScheduledCount = 0;
        long maxScheduledDelay = 0;

        // First pass: collect and group all jobs
        for (int i = 0; i < timedJobs.size(); i++) {
            Job j = timedJobs.get(i);
            String jobName = j.getName();
            long delay = j.getTiming().getStartAfter() - now;
            boolean isDisabled = jobName.toLowerCase(java.util.Locale.US).contains("disabled");

            // Track max scheduled delay
            if (delay > maxScheduledDelay) maxScheduledDelay = delay;

            // Count eligible jobs (1s to 20s delay, not disabled)
            if (delay > 1000 && delay <= 20000 && !isDisabled) {
                totalQueueCounter.increment(jobName);
                eligibleScheduledCount++;
            }

            // Skip for display: non-positive, disabled, <1s, or >20s
            if (delay <= 0 || isDisabled || delay < 1000 || delay > 20000) {
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
        int activeRunners = _context.jobQueue().getActiveRunnerCount();
        int maxRunners = _context.jobQueue().getMaxRunnerCount();
        String maxDelayStr = " <span id=longest class=jobCounter style=float:right>" +
                             _t("Max wait: {0}", DataHelper.formatDuration2(maxScheduledDelay) + "</span>");
        scheduledBuf.append("<div class=tablewrap id=scheduled>\n<h3 id=scheduledjobs>")
           .append(_t("Scheduled jobs")).append(": ")
           .append(displayedJobCount).append(" / ").append(eligibleScheduledCount)
           .append(maxDelayStr)
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
            "<div class=tablewrap id=scheduled>\n<h3 id=scheduledjobs>" + _t("Scheduled jobs") + ": " +
            headerPlaceholder + maxDelayStr + "</h3>\n");

        if (eligibleScheduledCount <= 0) {
            buf.append("</div>");
            buf.setLength(0);
            return;
        } else {
            buf.append(scheduledBuf);
            getJobCounts(buf, totalQueueCounter, eligibleScheduledCount);
            buf.append("</div>");
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
        Collections.sort(names, new JobCountComparator(counter));

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

        List<JobStats> tstats = new ArrayList<JobStats>(_context.jobQueue().getJobStats());
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

        @Override
        public int compareTo(JobTimeEntry other) {
            // Sort by time (lowest delay first)
            if (this.time < other.time) return -1;
            if (this.time > other.time) return 1;
            // If same time, sort by job name
            return Collator.getInstance().compare(this.jobName, other.jobName);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof JobTimeEntry)) return false;
            JobTimeEntry other = (JobTimeEntry) o;
            return time == other.time && jobName.equals(other.jobName);
        }

        @Override
        public int hashCode() {
            int result = jobName.hashCode();
            result = 31 * result + Long.hashCode(time);
            return result;
        }
    }

}
