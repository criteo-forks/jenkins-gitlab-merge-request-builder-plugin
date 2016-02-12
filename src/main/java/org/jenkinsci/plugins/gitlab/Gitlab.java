package org.jenkinsci.plugins.gitlab;

import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabMergeRequest;
import org.gitlab.api.models.GitlabProject;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GitlabAPI Wrapper Class
 */
public class Gitlab {

    private final static Logger _logger = Logger.getLogger(Gitlab.class.getName());

    private GitlabAPI _api;
    private ProjectsCache _projectsCache;
    private OpenMergeRequestAsyncGetter _openMergeRequestGetter;

    private void connect() {
        String privateToken = GitlabBuildTrigger.getDesc().getBotApiTokenSecret().getPlainText();
        String apiUrl = GitlabBuildTrigger.getDesc().getGitlabHostUrl();
        _api = GitlabAPI.connect(apiUrl, privateToken);
        _projectsCache = new ProjectsCache(_api, 5 * 60 * 1000 /* 5 minutes */);
        _openMergeRequestGetter = new OpenMergeRequestAsyncGetter(_api, 2 * 60 * 1000 /* 2 minutes */);
    }

    public GitlabAPI get() {
        if (_api == null) {
            connect();
        }

        return _api;
    }

    public List<GitlabMergeRequest> getOpenMergeRequests(GitlabProject project) throws IOException {
        _logger.log(Level.FINE, this.toString() + ".getOpenMergeRequests(" + project.getId() + ")");
        return getOpenMergeRequestAsyncGetter().get(project);
    }

    private OpenMergeRequestAsyncGetter getOpenMergeRequestAsyncGetter() {
        if (_openMergeRequestGetter == null) {
            connect();
        }

        return _openMergeRequestGetter;
    }

    public List<GitlabProject> getCachedProjects() throws IOException {
        if (_projectsCache == null) {
            connect();
        }

        return _projectsCache.get();
    }

    private static class ProjectsCache {
        private final GitlabAPI _api;
        private final int _timeToLiveMs;

        private volatile long _lastUpdateTimeMs;
        private volatile List<GitlabProject> _projects;

        public ProjectsCache(GitlabAPI api, int timeToLiveInMilliseconds) {
            _api = api;
            _timeToLiveMs = timeToLiveInMilliseconds;
        }

        public List<GitlabProject> get() throws IOException {
            if (_projects == null || (System.currentTimeMillis() - _lastUpdateTimeMs) > _timeToLiveMs) {
                synchronized (this) {
                    if (_projects == null || (System.currentTimeMillis() - _lastUpdateTimeMs) > _timeToLiveMs) {
                        _projects = _api.getAllProjects();
                        _lastUpdateTimeMs = System.currentTimeMillis();
                    }
                }
            }
            return _projects;
        }
    }

    private static class OpenMergeRequestAsyncGetter {

        private final GitlabAPI _api;

        // Number of milliseconds to wait between two actual requests for the same project
        private final long _minDelayMs;

        // Requests for open MR (enqueued by callers, dequeued asynchronously by dedicated thread)
        // Ideally entries should be project Id (Integer), but the Gitlab API expects an entire
        // GitlabProject (even though the code behind only use the id)
        private final BlockingQueue<GitlabProject> _requests = new LinkedBlockingQueue<>();

        // Map associating Gitlab project ids to open merge requests obtained on last check
        private final ConcurrentHashMap<Integer, List<GitlabMergeRequest>> _responses = new ConcurrentHashMap<>();

        public OpenMergeRequestAsyncGetter(GitlabAPI api, long delayMs) {
            _api = api;
            _minDelayMs = delayMs;

            // This thread makes the actual requests
            new Thread(new Runnable() {

                // Map associating Gitlab project ids to the time of the last check
                private final HashMap<Integer, Long> _lastTime = new HashMap<>();

                public void run() {
                    try {
                        while (true) {
                            // Consume an external request
                            GitlabProject p = _requests.take();
                            try {
                                Long t = _lastTime.get(p.getId());
                                if (t == null || _minDelayMs < System.currentTimeMillis() - t) {
                                    _logger.log(Level.FINE, this.toString() + " actually getting open merge requests for " + p.getId());
                                    // (the GitlabProject instance is not aggregated by GitlabMergeRequests, and
                                    // only the project Id is actually used by getOpenMergeRequests
                                    _responses.put(p.getId(), _api.getOpenMergeRequests(p));
                                    _lastTime.put(p.getId(), System.currentTimeMillis());
                                }
                            } catch (IOException e) {
                                _logger.log(Level.SEVERE, e.getMessage());
                            }
                        }
                    } catch (InterruptedException e) {
                        _logger.log(Level.SEVERE, e.getMessage());
                    }
                }
            }).start();
        }

        public List<GitlabMergeRequest> get(GitlabProject project) throws IOException {
            _requests.add(project);
            final List<GitlabMergeRequest> l = _responses.get(project.getId());
            return l == null ? Collections.<GitlabMergeRequest>emptyList() : l;
        }

    }

}
