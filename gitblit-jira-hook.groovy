import com.gitblit.GitBlit
import com.gitblit.utils.JGitUtils
import org.eclipse.jgit.lib.Repository
import org.slf4j.Logger

@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1' )
import groovyx.net.http.RESTClient
import groovyx.net.http.HttpResponseException
import groovyx.net.http.ContentType

@Grab(group='com.google.gdata', module='core', version='1.47.1')
import com.google.gdata.client.authn.oauth.RsaSha1PrivateKeyHelper
import com.google.gdata.client.authn.oauth.OAuthRsaSha1Signer
import com.google.gdata.client.authn.oauth.OAuthParameters
import com.google.gdata.client.authn.oauth.OAuthUtil

/*

Gitblit Post-Receive Hook: jira-comment

The purpose of this script is to invoke the jira API and add a comment to an issue

This script is only executed when pushing to *Gitblit*, not to other Git tooling you may be using.

Setup:
1. Generate an RSA pub/priv key pair (Atlassian's OAuth provider uses RSA-SHA1 to sign the request)
 openssl genrsa 1024 | openssl pkcs8 -topk8 -nocrypt -out rsa.pem
 openssl rsa -in rsa.pem -pubout > rsa.pub

2. Configure an Application Link.
 To register an OAuth consumer, you'll need to register an Application Link inside your Atlassian product.

3. After you've created an Application Link, configure an "Incoming Authentication" with the following details:
 Consumer key:             gitblit
 Consumer name:            gitblit
 Public key:               <paste the contents of rsa.pub>
 Alllow 2-Legged OAuth     true
 Allow user impersonation  true

4. Set parameters in hook according to your environment

*/

logger.info("jira comment hook triggered by ${user.username} for ${repository.name}")

Repository repo = gitblit.getRepository(repository.name)

def jiraUrl = "http://localhost:5000"
def consumerKey = "gitblit"
def jiraRepoRegex = "\\b([A-Z]+)-(\\d+)\\b"
def gitwebUrl = "http://localhost:8080/gitblit"
def privKeyFile = "/srv/gitblit/data/rsa.pem"

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
        "*message*: " + commitMessage.minus(match[0][0])

      OAuthRsaSha1Signer rsaSigner = new OAuthRsaSha1Signer();
      rsaSigner.setPrivateKey(RsaSha1PrivateKeyHelper.getPrivateKeyFromFilename(privKeyFile));

      OAuthParameters params = new OAuthParameters();
      params.setOAuthConsumerKey(consumerKey);
      params.setOAuthNonce(OAuthUtil.getNonce());
      params.setOAuthTimestamp(OAuthUtil.getTimestamp());
      params.setOAuthSignatureMethod("RSA-SHA1");
      params.setOAuthType(OAuthParameters.OAuthType.TWO_LEGGED_OAUTH);
      params.setOAuthToken("");
      params.addCustomBaseParameter("user_id", "${user.username}");

      String paramString = params.getBaseParameters().sort().collect{it}.join('&')
      String baseString = [OAuthUtil.encode("POST")
        ,OAuthUtil.encode(jiraUrl+issueUrl)
        ,OAuthUtil.encode(paramString)].join('&')

      String signature = rsaSigner.getSignature(baseString, params);
      params.addCustomBaseParameter("oauth_signature", signature);

      logger.info("restClient.post to ${issueUrl}")
      def restClient = new RESTClient(jiraUrl)
      try {
        def resp = restClient.post(
        path : issueUrl,
        body : [body:commitMessageWithLink],
        query : params.getBaseParameters(),
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