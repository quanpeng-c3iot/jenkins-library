package c3.groovy.lib

class Util implements Serializable {

  def steps

  Util(steps) {this.steps = steps}

  def gitPull(REPO, SHA) {
    sh """
        cd ${REPO}
        git reset --hard -q
        git pull -q
        git checkout ${SHA}
     """.stripIndent()
  }

  def gitCheckout(REPO, SHA, URL, CHANGELOG) {
    checkout(changelog: CHANGELOG,
            poll: false,
            scm: [$class                           : 'GitSCM',
                  branches                         : [[name: SHA]],
                  doGenerateSubmoduleConfigurations: false,
                  extensions                       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: REPO],
                                                      [$class: 'CloneOption', noTags: true, reference: '', shallow: true, depth: 20]],
                  submoduleCfg                     : [],
                  userRemoteConfigs                : [[url: URL]]])
  }

  def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
  }

  def appendPrefix(prefix, files) {
    try {
      sh """
          sed -i "/\\(<testcase .*classname=['\\\"]\\)\\(.*\\)\\(['\\\"]\\)/s/\\./_/g" $files
          sed -i "s/\\(<testcase .*classname=['\\\"]\\)\\([a-z]\\)/\\1${prefix}.\\2/g" $files
          """
    } catch (err) {

    }
  }

  def applyGitHubTag(API_URL, TAG_NAME, SHA) {
    withCredentials([string(credentialsId: 'c3ci_token', variable: 'TOKEN')]) {
      sh """
        TAG_INFO='{ "tag": "${TAG_NAME}", "message": "${TAG_NAME}", "object": "${SHA}","type": "commit"}'
        # create tag object
        curl -s -X POST -H "Authorization: ${TOKEN}" "${API_URL}/git/tags" -d "\${TAG_INFO}" > response.json
        TAG_SHA=\$(node -e 'console.log(require("./response.json").sha)')
        # check results
        if [ -z "\${TAG_SHA}" ]; then
          echo "Error during creation tag object:"
          cat response.json
          echo "Tag info: \${TAG_INFO}"
        fi
        TAG_INFO="{\\"ref\\": \\"refs/tags/${TAG_NAME}\\",\\"sha\\": \\"\${TAG_SHA}\\"}"
        # try to remove ref first:
        curl -s -X DELETE -H "Authorization: ${TOKEN}" "${API_URL}/git/refs/tags/${TAG_NAME}" > response.json
        if [ "\$(node -e 'console.log(require("./response.json").message)' 2>/dev/null || true)" == "Reference does not exist" ]; then
          echo "Creating new reference refs/tags/${TAG_NAME} -> ${SHA}"
        else
          echo "Updating reference refs/tags/${TAG_NAME} -> ${SHA}"
        fi
        # create tag ref
        curl -s -X POST -H "Authorization: ${TOKEN}" "${API_URL}/git/refs" -d "\${TAG_INFO}" > response.json
        # check results
        if [ "\$(node -e 'console.log(require("./response.json").ref)')" == 'undefined' ]; then
          echo "Error during creation reference refs/tags/${TAG_NAME} for tag object \${TAG_SHA}:"
          cat response.json
          exit 1
        fi
        rm -f response.json
      """.stripIndent()
    }
  }

  def addArtifactSummary(RPM) {
    def summary = manager.createSummary('installer.png');
    summary.appendText("<span>Server RPM: </span><br/><a href=\"https://artifacts.c3-e.com/v1/artifacts/rpm-repo/rpms/${RPM}\">${RPM}</a>", false)
    summary.appendText('<br/><br/>', false);
  }

  def archiveLogsToS3(FILE_NAME) {
    sh """
      LOGDIR=${FILE_NAME}-logs
      mkdir -p \${LOGDIR}/{server,cassandra,postgres}
  
      # collect server logs
      for l in server cassandra postgres; do
      cp /usr/local/share/c3/\${l}/log/* \${LOGDIR}/\${l}/ || true
      done
      cp /home/c3/c3log/c3_server.log \${LOGDIR}/server/ || true
      zip -r \${LOGDIR}.zip \${LOGDIR}
      rm -rf *-logs
      aws s3 mv \${LOGDIR}.zip s3://c3.internal.development.file.repository/file-repo/files/
    """.stripIndent()
    echo "Server Logs are stored in https://artifacts.c3-e.com/v1/artifacts/file-repo/files/" + FILE_NAME + "-logs.zip"
  }

  def customRetry(int retryLimit, nodeName, stageName, int timeout, timeoutUnit, codeLogic) {
    for (int i = 0; i < retryLimit; i++) {
      try {
        steps.timeout(time: timeout, unit: timeoutUnit) {
          steps.node(nodeName) {
            steps.stage(stageName) {
              codeLogic()
            }
          }
        }
      } catch (Throwable error) {
        echo "Step failed with error: " + error.toString()
        if (i + 1 == retryLimit) {
          echo "Couldn't retry any more, throwing error: " + error.toString()
          throw error
        }
        if (
        error.getClass().getName() == 'hudson.remoting.ChannelClosedException' ||
                error.getCause().getClass().getName() == 'hudson.remoting.ChannelClosedException' ||
                error.getClass().getName() == 'java.nio.channels.ClosedChannelException' ||
                error.getClass().getName() == 'hudson.remoting.RequestAbortedException'
        ) {
          echo "Retrying step. Attempt #" + (i + 2)
        } else {
          throw error
        }
      }
    }
  }


  def startServer(RPM_DEVELOPMENT_BUCKET, SERVER_RPM_FILE) {
    try {
      sh """
           set -e
           aws s3 cp s3://${RPM_DEVELOPMENT_BUCKET}/${SERVER_RPM_FILE} ${WORKSPACE}/${SERVER_RPM_FILE}
           yum localinstall -y ${WORKSPACE}/${SERVER_RPM_FILE}
           export C3_APPS_ROOT=${WORKSPACE}/c3base
           aws s3 cp s3://c3-devops-secure-vault/server-config.xml.custom /usr/local/share/c3/server/conf/server-config.xml.custom
           export C3_ENV=dev
           export VM_MIN_MEM="11G"
           export VM_MAX_MEM="27G"
           export VM_ARGS="-Djute.maxbuffer=131072"
           c3-server start -w -a 2> ${WORKSPACE}/err && c3-qe-util hostinfo
           c3-selenium start -x
        """.stripIndent();
    }
    catch (Throwable error) {
      echo "Server startup failed " + error.toString();
      throw error;
    }

  }

  def provision(PACKAGE) {
    try {
      sh """
           cd c3base
           c3-prov tenant -u BA -p BA -t reference 2> ${WORKSPACE}/err
           c3-prov tenant -u BA -p BA -t c3e 2> ${WORKSPACE}/err
           c3-prov tag -u BA -p BA -t ${PACKAGE} -g prod 2> ${WORKSPACE}/err
           c3-prov data -u BA -p BA -t ${PACKAGE} -g prod -E 2> ${WORKSPACE}/err
           c3-qe-util waitforqueues -r -i 10 -t 1200 -p --fail-on-errors 2> ${WORKSPACE}/err
        """.stripIndent()
    }
    catch (Throwable error) {
      echo "Server provisioning failed " + error.toString();
      throw error;
    }
  }

  def testPackage(PACKAGE) {
    try {
      wrap([$class: 'AnsiColorBuildWrapper', colorMapName: 'xterm']) {
        sh """
           cd c3base
           c3-tester-node -u BA -p BA -t ${PACKAGE} -g prod -b -o ${PACKAGE} --skip-flippers -- ${
          WORKSPACE
        }/c3base/base/${PACKAGE}/test/jasmine/ 2> ${WORKSPACE}/err
        """.stripIndent()
      }
    }
    catch (Throwable error) {
      echo "Testing package " + PACKAGE + " failed " + error.toString();
      throw error;
    }
  }

  def validateSource(RPM_FILE) {
    sh """
        aws s3 cp ${RPM_FILE} .
     """.stripIndent()
    whitesource jobApiToken: '', jobCheckPolicies: 'global', jobForceUpdate: 'global', libExcludes: '', libIncludes: '**/*.jar, **/*.aar, **/*.dll, **/*.tar.gz, **/*.egg, **/*.whl, **/*.rpm, **/*.tar.bz2, **/*.tgz, **/*.deb, **/*.gzip, **/*.gem, **/*.swf, **/*.swc', product: '', productVersion: '', projectToken: '', requesterEmail: 'infosec@c3iot.com'
    sh """
        rm -rf *.rpm
     """.stripIndent()
  }

  def serverTest(TEST_COMMAND, TIME_OUT) {
    def collectTestResults = true
    try {
      timeout(time: TIME_OUT, unit: 'HOURS') {
        def customConfig = "server-config.xml.custom"
        if (TEST_COMMAND == 'integ prod')
          customConfig = 'server-config.xml.custom.prod'
        dir('c3server') {
          sh "aws s3 cp s3://c3-devops-secure-vault/${customConfig} ./config/server-config.xml.custom"
          sh "./c3-server ${TEST_COMMAND} -q -a"
        }
      }
    } catch (hudson.AbortException e) {
      def m = e.message =~ /(?i)script returned exit code (\d+)/
      if (m) {
        def exitcode = m.group(1).toInteger()
        if (exitcode == 1) {
          currentBuild.result = "UNSTABLE"
        } else {
          currentBuild.result = "FAILURE"
          error "Aborting build due to Exception " + exitcode
          collectTestResults = false
        }
      }
    }
    catch (Exception e) {
      collectTestResults = false
      throw e
    }
    finally {
      if (collectTestResults)
        junit '**/TEST-*.xml'
    }
  }

  def serverBuild() {
    sh """
        set -e
        cd c3server
        ./c3-server rebuild
        ./c3-server rebuild
        ./c3-prov install
     """.stripIndent()
  }
}