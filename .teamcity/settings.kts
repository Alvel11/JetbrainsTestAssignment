import jetbrains.buildServer.configs.kotlin.*
import jetbrains.buildServer.configs.kotlin.buildFeatures.perfmon
import jetbrains.buildServer.configs.kotlin.buildSteps.maven
import jetbrains.buildServer.configs.kotlin.buildSteps.script
import jetbrains.buildServer.configs.kotlin.triggers.vcs

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2025.07"

project {

    buildType(Build)
}

object Build : BuildType({
    name = "Build"

    artifactRules = """
        release-artifact.tar.gz
    """.trimIndent()
    publishArtifacts = PublishMode.SUCCESSFUL

    params {
        text("env.MAX_RETRIES", "5", allowEmpty = false)
        text("env.STARTING_BACKOFF", "1", allowEmpty = false)
        select("env.RELEASE_NOTES_URL",
            display = ParameterDisplay.PROMPT,
            value = "http://nginx/release-notes.txt",
            options = listOf(
                "http://nginx/release-notes.txt" to "http://nginx/release-notes.txt",
                "http://nginx/release-notes-v2.txt" to "http://nginx/release-notes-v2.txt",
                "http://nginx/release-notes-fail" to "http://nginx/release-notes-fail"
            )
        )

    }

    vcs {
        root(DslContext.settingsRoot)
        cleanCheckout = true
    }

    steps {
        script {
            name = "Checkout"
            id = "Checkout"
            scriptContent = """
                git fetch --all
                if ! git cat-file -e %git.commit.hash%^{commit}; then
                    exit 1
                fi
                git checkout %git.commit.hash%
            """.trimIndent()
        }
        maven {
            name = "Javadoc"
            id = "Javadoc"
            goals = "dokka:javadoc"
            runnerArgs = "-Dproject.build.outputTimestamp=1981-01-01T00:00:00Z"
        }
        script {
            name = "DownloadReleaseNotes"
            id = "DownloadReleaseNotes"
            scriptContent = """
                
                BACKOFF=%env.STARTING_BACKOFF%
                mkdir -p release-artifact
                
                for ((i=1; i<=%env.MAX_RETRIES%; i++)); do
                  if curl -sf %env.RELEASE_NOTES_URL% -o release_notes.txt; then
                    CHECKSUM=${'$'}(sha256sum release_notes.txt | awk '{print ${'$'}1}')
                    echo "Downloaded release notes, checksum: ${'$'}CHECKSUM"
                    mv release_notes.txt  release-artifact/
                    
                    # Move Javadoc to release artifact
                    cp -r target/dokkaJavadoc/* release-artifact/
                    
                    tar --sort=name --mtime='1970-01-01' --owner=0 --group=0 --numeric-owner -cf - -C release-artifact . | gzip -n > release-artifact.tar.gz
                
                  else
                    echo "Website unavailable, retrying in ${'$'}BACKOFF seconds"
                    BACKOFF=BACKOFF*2
                  fi
                fi
                
                echo "##teamcity[message text='Marketing release notes download failed. Please try at a later date or a manual upload.' status='ERROR']"
                exit 1
                
                
            """.trimIndent()
        }
    }

    features {
        perfmon {
        }
    }

    dependencies {
        artifacts(RelativeId("Build")) {
        }
    }
})
