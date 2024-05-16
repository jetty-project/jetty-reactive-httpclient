#!groovy

pipeline {
  agent none
  // save some io during the build
  options {
    skipDefaultCheckout()
    durabilityHint('PERFORMANCE_OPTIMIZED')
    buildDiscarder logRotator( numToKeepStr: '60' )
    disableRestartFromStage()
  }

  stages {
    stage("Parallel Stage") {
      parallel {
        stage("Build / Test / Javadoc - JDK21") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk21", "clean install -Dmaven.test.failure.ignore=true -e ", "maven3", false)
            }
          }
        }
        stage("Build / Test / Javadoc - JDK17") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk17", "clean install -Dmaven.test.failure.ignore=true -e javadoc:javadoc", "maven3", true)
            }
          }
        }
        stage("Build / Test / Javadoc - JDK11") {
          agent { node { label 'linux' } }
          steps {
            timeout( time: 180, unit: 'MINUTES' ) {
              checkout scm
              mavenBuild( "jdk11", "clean install -Dmaven.test.failure.ignore=true -e javadoc:javadoc", "maven3", true)
            }
          }
        }
      }
    }
  }
}

def mavenBuild(jdk, cmdline, mvnName, skipJacoco) {
  script {
    try {
      withEnv(["JAVA_HOME=${ tool "$jdk" }",
               "PATH+MAVEN=${ tool "$jdk" }/bin:${tool "$mvnName"}/bin",
               "MAVEN_OPTS=-Xms3g -Xmx3g -Djava.awt.headless=true -client -XX:+UnlockDiagnosticVMOptions -XX:GCLockerRetryAllocationCount=100"]) {
      configFileProvider(
        [configFile(fileId: 'oss-settings.xml', variable: 'GLOBAL_MVN_SETTINGS')]) {
          sh "mvn $cmdline -ntp -s $GLOBAL_MVN_SETTINGS -V -B -e -U $cmdline"
        }
      }
    }
    finally
    {
          junit testResults: '**/target/surefire-reports/*.xml,**/target/invoker-reports/TEST*.xml', allowEmptyResults: true
      if(!skipJacoco) {
          // Collect the JaCoCo execution results.
          jacoco inclusionPattern: '**/org/eclipse/jetty/reactive/**/*.class',
                  execPattern: '**/target/jacoco.exec',
                  classPattern: '**/target/classes',
                  sourcePattern: '**/src/main/java'
      }
    }
  }
}