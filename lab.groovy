def SCAN_REPOSITORY="trendsh-uno-melon"

def SMART_CHECK_SERVER="dssc.brycehawk.com"
def AWS_REGION="ap-southeast-1"
def SCAN_REGISTRY="650143975734.dkr.ecr.ap-southeast-1.amazonaws.com"

node {
 
    cleanWs()
    stage "Checkout Code"
    sh "echo $SCAN_REPOSITORY"
    sh "printenv"
    
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: '*/master']], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: '.']], submoduleCfg: [], userRemoteConfigs: [[credentialsId: 'jenkinsPW', url: 'https://github.com/ds-amea/monaco-melon']]]

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
            credentialsId: "brycehawk-api",
            usernameVariable: "DSSC_USER",
            passwordVariable: "DSSC_PASSWORD",
        ])
    ]){
        withCredentials([
            usernamePassword([
                credentialsId: "aws_eks_username",
            usernameVariable: "REGISTRY_USER",
            passwordVariable: "REGISTRY_PASSWORD",
            ])
        ]){
            smartcheckScan([
                imageName: SCAN_IMAGE,
                smartcheckHost: SMART_CHECK_SERVER,
                smartcheckUser: DSSC_USER,
                smartcheckPassword: DSSC_PASSWORD,
                insecureSkipTLSVerify: true,
                imagePullAuth: new groovy.json.JsonBuilder([
                    username: REGISTRY_USER,
                    password: REGISTRY_PASSWORD,
                ]).toString(),
            ])
        }
    }

    stage "Certify Release"
    sh "docker tag $SCAN_REPOSITORY:latest $SCAN_REGISTRY/$SCAN_REPOSITORY:$BRANCH_NAME"
    stage "Deploy to Production"

    sh "docker push $SCAN_REGISTRY/$SCAN_REPOSITORY:$BRANCH_NAME"
}
