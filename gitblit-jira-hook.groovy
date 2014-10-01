import com.gitblit.GitBlit
import com.gitblit.utils.JGitUtils
import org.eclipse.jgit.lib.Repository
import org.slf4j.Logger

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1' )
import groovyx.net.http.RESTClient
import groovyx.net.http.HttpResponseException
import groovyx.net.http.ContentType

/*

Gitblit Post-Receive Hook: jira-comment

The purpose of this script is to invoke the jira API and add a comment to an issue

This script is only executed when pushing to *Gitblit*, not to other Git tooling you may be using.

Setup:
1. Create a new Jira service user 

2. Set parameters in hook according to your environment

*/

logger.info("jira comment hook triggered by ${user.username} for ${repository.name}")

Repository repo = gitblit.getRepository(repository.name)

def jiraUrl = "http://localhost:5000"
def jiraRepoRegex = "\\b([A-Z]+)-(\\d+)\\b"
def jiraServiceUserName = "gitblit"
def jiraServiceUserPassword = "gitblit"
def gitwebUrl = "http://localhost:8080/gitblit"

for (command in commands) {
  def commits = JGitUtils.getRevLog(repo, command.oldId.name, command.newId.name).reverse()
  for (commit in commits) {

    def commitMessage = commit.getFullMessage().trim()

    if ((match = commitMessage =~ jiraRepoRegex)) {
      def project = match[0][1]
      def issueNo = match[0][2]
      def issueUrl = "/rest/api/2/issue/$project-$issueNo/comment"
      def repoName = repository.name
      def updatedRef = command.refName
      def commitRev = commit.getId().getName()
      def revUrl = "$gitwebUrl/commit/$repoName/$commitRev"
      String commitMessageWithLink = "*git commit* in: ${updatedRef} \n"+
        "[$repoName $commitRev|$revUrl]\n"+
        "*message*: " + commitMessage

     
      logger.info("restClient.post to ${issueUrl} with service user ${jiraServiceUserName}")
      def restClient = new RESTClient(jiraUrl)
      restClient.headers['Authorization'] = "Basic "+"${jiraServiceUserName}:${jiraServiceUserPassword}".getBytes('utf-8').encodeBase64()
      try {
        def resp = restClient.post(
        path : issueUrl,
        body : [body:commitMessageWithLink],
        requestContentType : ContentType.JSON )

        assert resp.status == 201
      }
      catch(HttpResponseException ex) {
        println ex.response.data
      }
    }
  }
}
repo.close()