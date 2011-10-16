/*
 * Copyright (C) 2011 JFrog Ltd.
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

import com.google.common.collect.HashMultimap
@Grapes([
@Grab(group = 'org.codehaus.groovy.modules.http-builder', module = 'http-builder', version = '0.5.1', root = 'http://repo.jfrog.org/artifactory/'),
@Grab(group = 'org.ccil.cowan.tagsoup', module = 'tagsoup', version = '1.2.1', root = 'http://repo.jfrog.org/artifactory/')
]) import groovy.xml.MarkupBuilder
import groovyx.net.http.HTTPBuilder
import org.apache.http.HttpRequestInterceptor
import org.apache.http.entity.InputStreamEntity
import org.artifactory.checksum.ChecksumsInfo
import org.artifactory.resource.ResourceStreamHandle
import static groovyx.net.http.ContentType.*
import static groovyx.net.http.Method.*
import static org.artifactory.repo.RepoPathFactory.create
import static org.artifactory.util.PathUtils.getExtension

executions {

    /**
     * Artifactory User Execution Plugin for pushing artifacts to Staging Repository (OSO).
     * 1. Setup:
     *   1.1. Place this script under ${ARTIFACTORY_HOME}/etc/plugins.
     *   1.2. Place profile file under ${ARTIFACTORY_HOME}/etc/stage.
     *          Profile file should be a Java properties file and contain 3 parameters: stagingUrl, stagingUsername and stagingPassword
     *          Example for local Nexus install with default credentials:
     *              stagingUrl=http://localhost:8081/nexus
     *              stagingUsername=admin
     *              stagingPassword=admin123
     *
     * 2. Execute POST request authenticated with Artifactory admin user with the following parameters separated by pipe (|):
     *  2.1. 'stagingProfile': name of the profile file (without the 'properties' extension).
     *      E.g. for profile saved in ${ARTIFACTORY_HOME}/etc/stage/localOsoDefaultCreds.properties the parameter will be profile=localOsoDefaultCreds
     *  2.2. Query parameters can be one of the two:
     *      2.2.1. By directory: defined by parameter 'dir'. The format of the parameter is repo-key/relative-path.
     *          It's the desired directory URL just without the base Artifactory URL.
     *          E.g. dir=lib-release-local/org/spacecrafts/spaceship-new-rel/1.0
     *      2.2.2. By build properties: any number of 'property=value1,value2,valueN' pairs are allowed, applying "AND" clause both on properties and on property values,
     *      where the 'property' is the full name of Artifactory property (inc. set name).
     *          All artifacts with combination of those properties will be pushed.
     *          E.g. build.name=spaceship-new-rel|build.number=143
     *  2.3. 'close': whether the staging repository should be closed or not.
     *      Boolean expression, true by default - the repository will be closed.
     *
     * 3. Examples of the request using CURL:
     *  3.1. Query by directory, upload only (without closing):
     *      curl -X POST -v -u admin:password "http://localhost:8090/artifactory/api/plugins/execute/osoPush?params=stagingProfile=localOsoDefaultCreds|close=false|dir=lib-release-local%2Forg%spacecrafts%2Fspaceship-new-rel%2F1.0"
     *  3.2. Query by properties:
     *      curl -X POST -v -u admin:password "http://localhost:8090/artifactory/api/plugins/execute/osoPush?params=stagingProfile=localOsoDefaultCreds|build.name=spaceship-new-rel|build.number=143"
     * */
    osoPush() { params ->
        try {

            //Defaults for success
            status = 200
            message = 'Artifact successfully staged at OSO'

            binding.warnings = []
            binding.knownParams = ['stagingProfile': params.stagingProfile, 'async': params.async, 'close': params.close]

            binding.stagingProps = validate params

            searchResults = findArtifactsBy params

            lookupRepoPath = uploadToStagingRepo searchResults

            if (!params.close || params.close[0] != 'false') {
                //staging repo can be found only if some file was deployed.
                if (lookupRepoPath) {
                    closeStagingRepoWith lookupRepoPath
                } else {
                    handleWarning('No upload occurred. Please check the query parameters.')
                }

                if (warnings) {
                    message = warnings
                    status = 500
                }
            } else {
                message = 'Artifact uploaded to OSO, but according to \'close\' parameter the staging repo wasn\'t closed.'
            }

        } catch (OsoPushException e) { //aborts during execution
            status = e.status
            message = e.message
        }
    }
}

def validate(params) throws OsoPushException {
    if (!params) handleError 400, 'Profile and query parameters are mandatory. Please supply them.'
    if (!params.stagingProfile) handleError 400, 'Profile name is mandatory. Please supply it.'
    //noinspection GroovyAssignabilityCheck
    File propertiesFile = new File(ctx.artifactoryHome.etcDir, "stage/${params.stagingProfile[0]}.properties")
    if (!propertiesFile.isFile()) handleError 400, "No profile properties file was found at ${propertiesFile.absolutePath}"
    Properties stagingProps = new Properties()
    stagingProps.load(new FileReader(propertiesFile))
    if (!stagingProps) handleError 400, "Failed to load properties file at ${propertiesFile.absolutePath}. Are the permissions right and is it properly formatted?"
    if (!stagingProps.stagingUrl) handleError 400, "Staging Server url is missing from profile properties file. Please add 'stagingUrl' property to ${propertiesFile.absolutePath}"
    if (!stagingProps.stagingUsername) handleError 400, "Staging Server username is missing from profile properties file. Please add 'stagingUsername' property to ${propertiesFile.absolutePath}"
    if (!stagingProps.stagingPassword) handleError 400, "Staging Server password is missing from profile properties file. Please add 'stagingPassword' property to ${propertiesFile.absolutePath}"
    def queryParams = params - knownParams
    if (!queryParams) handleError 400, 'Query string is missing from parameters. Please supply \'dir\' or one or more properties.'
    stagingProps
}

def findArtifactsBy(Map params) {
    assert params
    def queryParams = params - knownParams
    assert queryParams //at least one param expected being it 'dir' or some property
    def searchResults
    if (queryParams.dir) {
        String dir = queryParams.dir[0]
        def parts = dir.tokenize('/') //first part is repoKey
        if (parts.size() < 2) {
            handleError 400, "'${dir}' is invalid directory format. Should be 'repoKey/relativePath'."
        }
        def path = dir - parts[0]
        collectFiles(repositories.getItemInfo(create(parts[0], path)), []).collect {it.repoPath}
    } else { //we now only have properties in params
        //noinspection GroovyAssignabilityCheck
        searches.itemsByProperties(queryParams.inject(HashMultimap.create()) {query, entry ->
            query.putAll entry.key, entry.value //convert [:[]] parameters to SetMulimap acepted by searches
            query
        }).grep {repoPath -> //filter files only
            !repositories.getItemInfo(repoPath).folder
        }
    }
}

//get only files, not directories
def collectFiles(item, files) {
    children = repositories.getChildren(item.repoPath)
    if (children) {
        children.each {child ->
            collectFiles(child, files)
        }
    } else {
        files << item
    }
    files
}

def uploadToStagingRepo(searchResults) {
    assert searchResults != null

    def referenceFileRepoPath = null
    def backupFileRepoPath = null
    searchResults.each {repoPath ->
        def artifactUrl = "${stagingProps.stagingUrl}/service/local/staging/deploy/maven2/${repoPath.path}"
        ResourceStreamHandle content = repositories.getContent(repoPath)
        def http = new HTTPBuilder(artifactUrl)
        //we don't want to send big jar only to get auth challenge back, so we need preemptive authentication
        http.client.addRequestInterceptor({def httpRequest, def httpContext ->
            httpRequest.addHeader('Authorization', "Basic ${"${stagingProps.stagingUsername}:${stagingProps.stagingPassword}".getBytes().encodeBase64()}") //strange stuff! bytes won't work! only getBytes()
        } as HttpRequestInterceptor)
        //as opposite to ordinary input stream we do know the size, so we override regular binary encoder in the encoder registry.
        //TODO since we already messing with entity, configure it further to be repeatable org.apache.http.HttpEntity (as it is actually repeatable) for IO errors retries
        http.encoder.putAt(BINARY) {ResourceStreamHandle resourceStreamHandle ->
            new InputStreamEntity(resourceStreamHandle.inputStream, resourceStreamHandle.size)
        }
        try {
            http.request(PUT, BINARY) {
                body = content
                response.success = { resp ->
                    log.debug "Artifact ${repoPath.name} was successfully put in Staging Server"
                    backupFileRepoPath = repoPath
                    if (getExtension(repoPath.path).equalsIgnoreCase('pom')) { //we'd better go with pom
                        referenceFileRepoPath = repoPath
                    }
                }
                response.failure = { resp ->
                    handleError(resp, "Unexpected error while putting ${repoPath.name}")
                }
            }
        } finally {
            content.close()
        }
        ChecksumsInfo checksumsInfo = repositories.getFileInfo(repoPath).checksumsInfo
        putChecksums("${artifactUrl}.md5", checksumsInfo.md5)
        putChecksums("${artifactUrl}.sha1", checksumsInfo.sha1)
    }
    referenceFileRepoPath = referenceFileRepoPath ?: backupFileRepoPath //if no pom, let's go with some other file
    log.debug "The following file will be used for closing stage repository: ${referenceFileRepoPath}"
    referenceFileRepoPath
}

def putChecksums(url, checksum) {
    def http = new HTTPBuilder(url)
    http.auth.basic stagingProps.stagingUsername, stagingProps.stagingPassword
    http.request(PUT, TEXT) {
        body = checksum

        response.success = { resp ->
            log.debug "$checksum uploaded successfully"
        }

        response.failure = { resp ->
            handleWarning resp, "Unexpected error while uploading checksum ${checksum}. This might cause staging validation failures, and resulting in unclosed staging repo. Please validate closing manually. Error is"
        }
    }
}

def closeStagingRepoWith(lookupRepoPath) {
    assert lookupRepoPath

    //Staging server voodoo. Watch my fingers:
    //1. Find all the possible open staging repos and their staging profiles
    def stages = getOpenStages()

    //2. In those, find staging repo containing RepoPath in question
    def stage = findStageByRepoPath(stages, lookupRepoPath)

    if (!stage) {
        handleWarning "Can't find stage repository matching ${lookupRepoPath} to close. Please close it manually."
        return
    }

    //3. Prepare XML instruction for closing the found repo & submit it to appropriate staging profile
    closeRepo(stage)
}

def getOpenStages() {
    def stages = []
    def http = new HTTPBuilder("${stagingProps.stagingUrl}/service/local/staging/profiles")
    http.auth.basic stagingProps.stagingUsername, stagingProps.stagingPassword
    http.request(GET, XML) {
        response.success = { resp, stagingProfiles ->
            stagingProfiles.data.stagingProfile.each {profile ->
                def profileId = profile.id.text()
                log.debug "Found stage profile $profileId"
                profile.stagingRepositoryIds.string.each { stagingRepositoryId ->
                    def stageId = stagingRepositoryId.text()
                    log.debug "\tFound open stage $stageId"
                    stages << [profileId: profileId, stageId: stageId]
                }
            }
        }

        response.failure = { resp ->
            handleWarning resp, 'Error while getting open stage repositories. This might cause unclosed staging repo. Please validate closing manually. Error is'
        }
    }
    stages
}

def findStageByRepoPath(stages, repoPath) {
    assert stages != null //empty list is fine with us, but false by Groovy Truth
    assert repoPath

    stages.find {stage ->
        String stageRepoUrl = "${stagingProps.stagingUrl}/service/local/repositories/${stage.stageId}/content/${repoPath.path}?isLocal"
        def http = new HTTPBuilder(stageRepoUrl)
        http.auth.basic stagingProps.stagingUsername, stagingProps.stagingPassword
        def found = false
        http.request(HEAD) {
            response.success = {
                log.debug "${repoPath} found in ${stage}"
                found = true
            }

            response.'404' = {
                //fine with us, the the repoPath is not there
                log.debug "${repoPath} not found in ${stage}"
            }

            // handler for any failure status code except of 404:
            response.failure = { resp ->
                handleWarning resp, "Error while checking ${stage.stageId} repository for presence of ${repoPath}. This might cause unclosed staging repo. Please validate closing manually. Error is"
            }

        }
        found
    }
}

def closeRepo(stage) {
    assert stage
    assert stage.stageId
    assert stage.profileId

    //Build stage repository closing XML
    def writer = new StringWriter()
    def xml = new MarkupBuilder(writer)
    xml.promoteRequest {
        data {
            stagedRepositoryId stage.stageId
            description 'Staging completed'
        }
    }

    def http = new HTTPBuilder("${stagingProps.stagingUrl}/service/local/staging/profiles/${stage.profileId}/finish")
    http.auth.basic stagingProps.stagingUsername, stagingProps.stagingPassword
    http.request(POST, TEXT) {
        requestContentType = XML
        body = writer.toString()

        response.success = { resp ->
            log.debug 'Staging repo closed succussfully'
        }

        response.'400' = {resp, reader ->
            slurper = new XmlSlurper(new org.ccil.cowan.tagsoup.Parser())
            //noinspection GroovyAssignabilityCheck
            def html = slurper.parse reader
            handleError(400, html.toString().replaceAll('\u00A0', ' ')) //replace nbsp with regular ones
        }

        response.failure = { resp ->
            handleWarning resp, "Unexpected error while closing staging repo ${stage.stageId} for profile ${stage.profileId}. This might cause unclosed staging repo. Please validate closing manually. Error is"
        }
    }
}


def handleError(int status, message) throws OsoPushException {
    log.error message
    throw new OsoPushException(message: message, status: status)
}

def handleError(resp, message) throws OsoPushException {
    message += ": ${resp.statusLine.reasonPhrase}"
    handleError(((int) resp.statusLine.statusCode), message)
}

def handleWarning(message) {
    log.warn message
    warnings << message
}

def handleWarning(resp, message) {
    message += ": ${resp.statusLine.reasonPhrase}"
    handleWarning message
}

class OsoPushException extends Exception {
    def status
    def message
}