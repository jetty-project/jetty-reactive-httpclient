#!groovy

pipeline {
  agent none
  // save some io during the build
  options {
    skipDefaultCheckout()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    buildDiscarder logRotator( numToKeepStr: '30' )
    disableRestartFromStage()
  }

  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test / Javadoc - JDK17") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk17", "clean install -Dmaven.test.failure.ignore=true javadoc:javadoc -Djacoco.skip=true", "maven3", false)
            }
          }
        }
        stage("Build / Test / Javadoc - JDK21") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk21", "clean install -Dmaven.test.failure.ignore=true  javadoc:javadoc", "maven3", true)
            }
          }
        }
        stage("Build / Test / Javadoc - JDK23") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk23", "clean install -Dmaven.test.failure.ignore=true javadoc:javadoc -Djacoco.skip=true", "maven3", false)
            }
          }
        }
      }
    }
  }
}

def mavenBuild(String jdk, String cmdline, String mvnName, boolean recordJacoco) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${ tool "$jdk" }/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms3g -Xmx3g -Djava.awt.headless=true -client -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=100"]) {
      configFileProvider(
        [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn $cmdline -ntp -s $GLOBAL_MVN_SETTINGS -V -B -e -U"
        }
      }
    }
    finally
    {
      junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true
      if(recordJacoco) {
        // Collect the JaCoCo execution results.
        recordCoverage id: "coverage", name: "Coverage", tools: [[parser: 'JACOCO', pattern: '**/jacoco/jacoco.xml']], sourceCodeRetention: 'MODIFIED',
                        sourceDirectories: [[path: 'src/main/java']]
      }
    }
  }
}
