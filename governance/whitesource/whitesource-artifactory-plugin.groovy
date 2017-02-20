/**
 * Copyright (C) 2016 WhiteSource Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import groovy.transform.Field
import org.artifactory.common.*
import org.artifactory.fs.*
import org.artifactory.repo.*
import org.artifactory.build.*
import org.artifactory.exception.*
import org.artifactory.request.*
import org.artifactory.util.*
import org.artifactory.resource.*

import org.whitesource.agent.api.model.AgentProjectInfo
import org.whitesource.agent.client.WhitesourceService
import org.whitesource.agent.api.model.DependencyInfo
import org.whitesource.agent.api.model.Coordinates
import org.whitesource.agent.api.dispatch.CheckPolicyComplianceResult
import org.whitesource.agent.api.dispatch.GetDependencyDataResult
import org.whitesource.agent.api.model.ResourceInfo
import org.whitesource.agent.api.model.VulnerabilityInfo
import org.whitesource.agent.api.model.PolicyCheckResourceNode
import org.whitesource.agent.api.dispatch.UpdateInventoryRequest;
import org.whitesource.agent.api.dispatch.UpdateInventoryResult

import javax.ws.rs.core.*


@Field final String ACTION = 'WSS-Action'
@Field final String POLICY_DETAILS = 'WSS-Policy-Details'
@Field final String DESCRIPTION = 'WSS-Description'
@Field final String HOME_PAGE_URL = 'WSS-Homepage'
@Field final String LICENSES = 'WSS-Licenses'
@Field final String VULNERABILITY = 'WSS-Vulnerability: '
@Field final String VULNERABILITY_SEVERITY = 'WSS-Vulnerability-Severity: '
@Field final String CVE_URL = 'https://cve.mitre.org/cgi-bin/cvename.cgi?name='

@Field final String PROPERTIES_FILE_PATH = 'plugins/whitesource-artifactory-plugin.properties'
@Field final String AGENT_TYPE = 'artifactory-plugin'
@Field final String AGENT_VERSION = '2.2.7'
@Field final String OR = '|'
@Field final int MAX_REPO_SIZE = 10000
@Field final int MAX_REPO_SIZE_TO_UPLOAD = 2000

//    @Field final String PROJECT_NAME = 'ArtifactoryDependencies'
@Field final String BLANK = ''
@Field final String DEFAULT_SERVICE_URL = 'https://saas.whitesourcesoftware.com/agent'
@Field final String BOWER = 'bower'
@Field final String FORWARD_SLASH = '/'
@Field final String UNDERSCORE = '_'
@Field final String REJECT = 'Reject'
@Field final String ACCEPT = 'Accept'
@Field final int DEFAULT_CONNECTION_TIMEOUT_MINUTES = 60

/**
 * This is a plug-in that integrates Artifactory with WhiteSource
 * Extracts descriptive information from your open source libraries located in the Artifactory repositories
 * and integrates them with WhiteSource.
 *
 * The plugin will check each item details against the organizational policies
 * Check policies suggests information about the action (approve/reject),
 * and policy details as defined by the user in WhiteSource(for example : Approve some license)
 *  1. WSS-Action
 *  2. WSS-Policy-Details
 * Additional data for the item will be populated in your Artifactory property tab :
 * 1. WSS-Description
 * 2. WSS-HomePage
 * 3. WSS-Licenses
 * 4. WSS-Vulnerabilities
 */

jobs {
    /**
     * How to set cron execution:
     * cron (java.lang.String) - A valid cron expression used to schedule job runs (see: http://www.quartz-scheduler.org/docs/tutorial/TutorialLesson06.html)
     * 1 - Seconds , 2 - Minutes, 3 - Hours, 4 - Day-of-Month , 5- Month, 6 - Day-of-Week, 7 - Year (optional field).
     * Examples :
     * "0 42 9 * * ?"  - Build a trigger that will fire daily at 9:42 am
     * "0 0/2 8-17 * * ?" - Build a trigger that will fire every other minute, between 8am and 5pm, every day
     */
    updateRepoWithWhiteSource(cron: "0 27 17 * * ?") {
        try {
            log.info("Starting job updateRepoData By WhiteSource")
            def config = new ConfigSlurper().parse(new File(ctx.artifactoryHome.haAwareEtcDir, PROPERTIES_FILE_PATH).toURL())
            String[] repositories = config.repoKeys as String[]
            for (String repository : repositories) {
                Map<String, ItemInfo> sha1ToItemMap = new HashMap<String, ItemInfo>()
                findAllRepoItems(RepoPathFactory.create(repository), sha1ToItemMap)
                int repoSize = sha1ToItemMap.size()
                if (repoSize > MAX_REPO_SIZE) {
                    log.warn("The max repository size for check policies in WhiteSource is : ${repoPath} items, Job Exiting")
                } else if (repoSize == 0) {
                    log.warn("This repository is empty or not exit : ${repository} , Job Exiting")
                } else {
                    // create project and WhiteSource service
                    Collection<AgentProjectInfo> projects = createProjects(sha1ToItemMap, repository)
                    WhitesourceService whitesourceService = createWhiteSourceService(config)
                    // update WhiteSource with repositories with no more than 2000 artifacts
                    log.info("Sending Update to WhiteSource for repository : ${repository}")
                    if (repoSize > MAX_REPO_SIZE_TO_UPLOAD) {
                        log.warn("Max repository size inorder to update WhiteSource is : ${repoPath}")
                    } else {
                        UpdateInventoryResult updateResult = whitesourceService.update(config.apiKey, config.productName, BLANK, projects)
                        logResult(updateResult)
                    }
                    // check policies and add additional data for each artifact
                    setArtifactsPoliciesAndExtraData(projects, config, repository, whitesourceService, sha1ToItemMap)
                }
            }
        } catch (Exception e) {
            log.warn("Error while running the plugin: {}", e.getMessage())
        }
        log.info("Job updateRepoWithWhiteSource has Finished")
    }
}


storage {
    /**
     * Handle after create events.
     *
     * Closure parameters:
     * item (org.artifactory.fs.ItemInfo) - the original item being created.
     */
    afterCreate { item ->
        try {
            if (!item.isFolder()) {
                def config = new ConfigSlurper().parse(new File(ctx.artifactoryHome.haAwareEtcDir, PROPERTIES_FILE_PATH).toURL())
                Map<String, ItemInfo> sha1ToItemMap = new HashMap<String, ItemInfo>()
                sha1ToItemMap.put(repositories.getFileInfo(item.getRepoPath()).getChecksumsInfo().getSha1(), item)
                Collection<AgentProjectInfo> projects = createProjects(sha1ToItemMap, item.getRepoKey())
                WhitesourceService whitesourceService = createWhiteSourceService(config)
                setArtifactsPoliciesAndExtraData(projects, config, item.getRepoKey(), whitesourceService, sha1ToItemMap)
            }
        } catch (Exception e) {
            log.warn("Error while running the plugin: {$e.getMessage()}")
        }
        log.info("New Item - {$item} was added to the repository")
    }
}

/* --- Private Methods --- */

private void checkPolicies(Map<String, PolicyCheckResourceNode> projects, Map<String, ItemInfo> sha1ToItemMap){
    for (String key : projects.keySet()) {
        PolicyCheckResourceNode policyCheckResourceNode = projects.get(key)
        Collection<PolicyCheckResourceNode> children = policyCheckResourceNode.getChildren()
        for (PolicyCheckResourceNode child : children) {
            ItemInfo item = sha1ToItemMap.get(child.getResource().getSha1())
            if (item != null && child.getPolicy() != null) {
                def path = item.getRepoPath()
                if (REJECT.equals(child.getPolicy().getActionType()) || ACCEPT.equals(child.getPolicy().getActionType())) {
                    repositories.setProperty(path, ACTION, child.getPolicy().getActionType())
                    repositories.setProperty(path, POLICY_DETAILS, child.getPolicy().getDisplayName())
                }
            }
        }
    }
}

private updateItemsExtraData(GetDependencyDataResult dependencyDataResult, Map<String, ItemInfo> sha1ToItemMap){
    for (ResourceInfo resource : dependencyDataResult.getResources()) {
        ItemInfo item = sha1ToItemMap.get(resource.getSha1())
        if (item != null) {
            RepoPath repoPath = item.getRepoPath()
            if (!BLANK.equals(resource.getDescription())) {
                repositories.setProperty(repoPath, DESCRIPTION, resource.getDescription())
            }
            if (!BLANK.equals(resource.getHomepageUrl())) {
                repositories.setProperty(repoPath, HOME_PAGE_URL, resource.getHomepageUrl())
            }

            Collection<VulnerabilityInfo> vulns = resource.getVulnerabilities()
            for (VulnerabilityInfo vulnerabilityInfo : vulns) {
                String vulnName = vulnerabilityInfo.getName()
                repositories.setProperty(repoPath, VULNERABILITY + vulnName, "${CVE_URL}${vulnName}")
                repositories.setProperty(repoPath, VULNERABILITY_SEVERITY + vulnName, "${vulnerabilityInfo.getSeverity()}")
            }
            Collection<String> licenses = resource.getLicenses()
            String dataLicenses = BLANK
            for (String license : licenses) {
                dataLicenses += license + ", "
            }
            if (dataLicenses.size() > 0) {
                dataLicenses = dataLicenses.substring(0, dataLicenses.size() - 2)
                repositories.setProperty(repoPath, LICENSES, dataLicenses)
            }
        }
    }
}

private void findAllRepoItems(def repoPath, Map<String, ItemInfo> sha1ToItemMap) {
    for (ItemInfo item : repositories.getChildren(repoPath)) {
        if (item.isFolder()) {
            findAllRepoItems(item.getRepoPath(), sha1ToItemMap)
        } else {
            sha1ToItemMap.put(repositories.getFileInfo(item.getRepoPath()).getChecksumsInfo().getSha1(), item)
        }
    }
    return
}


private void setArtifactsPoliciesAndExtraData(Collection<AgentProjectInfo> projects, def config, String repoName,
                                              WhitesourceService whitesourceService,  Map<String, ItemInfo> sha1ToItemMap) {
    // get policies and dependency data result and update properties tab for each artifact
    try {
        int repoSize = sha1ToItemMap.size()
        log.info("Finished updating WhiteSource with ${repoSize} artifacts")
        GetDependencyDataResult dependencyDataResult = whitesourceService.getDependencyData(config.apiKey, config.productName, BLANK, projects)
        log.info("Updating additional dependency data")
        updateItemsExtraData(dependencyDataResult, sha1ToItemMap)
        log.info("Finished updating additional dependency data")
        if (config.checkPolicies) {
            CheckPolicyComplianceResult checkPoliciesResult = whitesourceService.checkPolicyCompliance(config.apiKey, config.productName, BLANK, projects, false)
            log.info("Updating policies for repository: ${repoName}")
            checkPolicies(checkPoliciesResult.getNewProjects(), sha1ToItemMap)
            checkPolicies(checkPoliciesResult.getExistingProjects(), sha1ToItemMap)
            log.info("Finished updating policies for repository : ${repoName}")
        }
    } catch (Exception e) {
        log.warn("Error while running the plugin: ${e.getMessage()}")
    }
}

private Collection<AgentProjectInfo> createProjects(Map<String, ItemInfo> sha1ToItemMap, String repoName) {
    Collection<AgentProjectInfo> projects = new ArrayList<AgentProjectInfo>()
    AgentProjectInfo projectInfo = new AgentProjectInfo()
    projects.add(projectInfo)
    projectInfo.setCoordinates(new Coordinates(null, repoName, BLANK))
    // Create Dependencies
    List<DependencyInfo> dependencies = new ArrayList<DependencyInfo>()
    for (String key : sha1ToItemMap.keySet()) {
        DependencyInfo dependencyInfo = new DependencyInfo(key)
        dependencyInfo.setArtifactId(sha1ToItemMap.get(key).getName())
        dependencies.add(dependencyInfo)
    }
    projectInfo.setDependencies(dependencies)
    return projects
}

private WhitesourceService createWhiteSourceService(def config) {
    String url = BLANK.equals(config.wssUrl) ? DEFAULT_SERVICE_URL : config.wssUrl
    boolean setProxy = false
    if (config.useProxy) {
        setProxy = true
    }
    WhitesourceService whitesourceService = null
    try {
        // create whiteSource service and check proxy settings
        whitesourceService = new WhitesourceService(AGENT_TYPE, AGENT_VERSION, url, setProxy, DEFAULT_CONNECTION_TIMEOUT_MINUTES)
        checkAndSetProxySettings(whitesourceService, config)

    } catch (Exception e) {
        log.warn("Error creating WhiteSource Service: {$e.getMessage()}")
    }
    return whitesourceService
}

private void checkAndSetProxySettings(WhitesourceService whitesourceService, def config) {
    if (config.useProxy) {
        log.info("Setting proxy settings")
        def proxyPort = config.proxyPort
        final String proxyHost = config.proxyHost
        final String proxyUser = null
        final String proxyPass = null
        if (!BLANK.equals(config.proxyUser) && !BLANK.equals(config.proxyPass)) {
            proxyUser = config.proxyUser
            proxyPass = config.proxyPass
        }
        whitesourceService.getClient().setProxy(proxyHost, proxyPort, proxyUser, proxyPass)
    }
}

private void logResult(UpdateInventoryResult updateResult) {
    StringBuilder resultLogMsg = new StringBuilder("Inventory update results for ").append(updateResult.getOrganization()).append("\n")

    // newly created projects
    Collection<String> createdProjects = updateResult.getCreatedProjects()
    if (createdProjects.isEmpty()) {
        resultLogMsg.append("No new projects found.").append("\n")
    } else {
        resultLogMsg.append("Newly created projects:").append("\n")
        for (String projectName : createdProjects) {
            resultLogMsg.append(projectName).append("\n")
        }
    }
    // updated projects
    Collection<String> updatedProjects = updateResult.getUpdatedProjects()
    if (updatedProjects.isEmpty()) {
        resultLogMsg.append("No projects were updated.").append("\n")
    } else {
        resultLogMsg.append("Updated projects:").append("\n")
        for (String projectName : updatedProjects) {
            resultLogMsg.append(projectName).append("\n")
        }
    }
    log.info(resultLogMsg.toString())
}