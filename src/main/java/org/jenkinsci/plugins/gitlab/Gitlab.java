package org.jenkinsci.plugins.gitlab;

import org.gitlab.api.GitlabAPI;
import org.gitlab.api.models.GitlabProject;

import java.io.IOException;
import java.util.List;

/**
 * GitlabAPI Wrapper Class
 */
public class Gitlab {
    private GitlabAPI _api;
    private ProjectsCache _projectsCache;

    private void connect() {
        String privateToken = GitlabBuildTrigger.getDesc().getBotApiTokenSecret().getPlainText();
        String apiUrl = GitlabBuildTrigger.getDesc().getGitlabHostUrl();
        _api = GitlabAPI.connect(apiUrl, privateToken);
        _projectsCache = new ProjectsCache(_api, 5 * 60 * 1000 /* 5 minutes */);
    }

    public GitlabAPI get() {
        if (_api == null) {
            connect();
        }

        return _api;
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
}
