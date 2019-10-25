def SCAN_REPOSITORY="monaco-x3-melon"
def SMART_CHECK_SERVER="ae97dc0fbf6f411e9829c0ad42a94809-175059656.ap-southeast-1.elb.amazonaws.com"
def AWS_REGION="ap-southeast-1"
def GIT_REPO="ssh://git-codecommit.ap-southeast-1.amazonaws.com/v1/repos/monaco-x3-melon"
def GIT_CREDENTIALS="GIT"
def DSSC_CREDENTIALS="DSSC"
def REPO_CREDENTIALS="ECR"
def SCAN_REGISTRY="650143975734.dkr.ecr.ap-southeast-1.amazonaws.com"
def BRANCH_NAME = "master"
import groovy.json.JsonOutput
node {
 
    cleanWs()
    stage "Checkout Code"
    sh "echo $SCAN_REPOSITORY"
    sh "printenv"
    
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: BRANCH_NAME]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '.']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: GIT_CREDENTIALS, url: GIT_REPO]]]

    stage "Build Image"
    
    sh "docker build -t $SCAN_REPOSITORY ."
    sh "printenv"
    stage "Send to Repository"
    sh "eval \$(aws ecr get-login --no-include-email --region $AWS_REGION | sed 's|https://||')"
    sh "docker tag $SCAN_REPOSITORY:latest $SCAN_REGISTRY/$SCAN_REPOSITORY:$BUILD_ID"
    sh "docker push $SCAN_REGISTRY/$SCAN_REPOSITORY:$BUILD_ID"
 
 

    stage "Smart Check"

     def SCAN_IMAGE="$SCAN_REGISTRY/$SCAN_REPOSITORY:$BUILD_ID"
     sh "echo $SCAN_IMAGE"


    withCredentials([
        usernamePassword([
            credentialsId: DSSC_CREDENTIALS,
            usernameVariable: "DSSC_USER",
            passwordVariable: "DSSC_PASSWORD",
        ])
    ]){
        withCredentials([
            usernamePassword([
                credentialsId: REPO_CREDENTIALS,
            usernameVariable: "Access_ID",
            passwordVariable: "Secret_ID",
            ])
        ]){
            smartcheckScan([
                imageName: SCAN_IMAGE,
                smartcheckHost: SMART_CHECK_SERVER,
                smartcheckUser: DSSC_USER,
                smartcheckPassword: DSSC_PASSWORD,
                insecureSkipTLSVerify: true,
                findingsThreshold: JsonOutput.toJson([
                    "malware": 1,
                    "vulnerabilities": [
                        "critical": 1,
                        "high": 1,
                    ],
                    "contents": [
                        "critical": 1,
                        "high": 1,
                    ]
                ]).toString(),
                imagePullAuth: JsonOutput.toJson([
                    "aws": [
						"region": "ap-southeast-1",
						"accessKeyID": Access_ID,
						"secretAccessKey": Secret_ID,
						]
                ]).toString(),
            ])
        }
    }
    sh "cat "
    stage "Certify Release"
    sh "docker tag $SCAN_REPOSITORY:latest $SCAN_REGISTRY/$SCAN_REPOSITORY:$BRANCH_NAME"
    stage "Deploy to Production"

    sh "docker push $SCAN_REGISTRY/$SCAN_REPOSITORY:$BRANCH_NAME"
}
