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
        javadoc.tar.gz
        cache/cache.json
        releases/
    """.trimIndent()

    params {
        text("env.RELEASE_NOTES_URL", "http://localhost:8081/release-notes.txt", readOnly = true, allowEmpty = true)
        text("env.CACHE_MAP", "cache/cache.json", readOnly = true, allowEmpty = true)
        text("git.commit.hash", "", display = ParameterDisplay.PROMPT, allowEmpty = true)
    }

    vcs {
        root(DslContext.settingsRoot)
    }

    steps {
        script {
            name = "Checkout"
            id = "Checkout"
            scriptContent = """
                git fetch --all
                git checkout %git.commit.hash%
            """.trimIndent()
        }
        maven {
            name = "Javadoc"
            id = "Javadoc"
            goals = "dokka:javadoc"
        }
        script {
            name = "JavadocArtifact"
            id = "JavadocArtifact"
            scriptContent = "tar -czf javadoc.tar.gz -C target/dokkaJavadoc ."
        }
        script {
            name = "DownloadReleaseNotes"
            id = "DownloadReleaseNotes"
            scriptContent = """
                sudo apt-get update
                sudo apt-get install -y jq
                
                
                mkdir -p cache
                if [ ! -f %env.CACHE_MAP% ]; then
                  echo "{}" > %env.CACHE_MAP%
                fi
                
                CACHED_REFERENCE=${'$'}(jq -r --arg commit %git.commit.hash% '.[${'$'}commit] // empty' %env.CACHE_MAP%)
                
                
                if [ "${'$'}CACHED_REFERENCE" != "" ]; then
                  # First case, artifact was first generated when the marketing website was not reachable
                  # No release notes are added to artifact to ensure reproducibility
                  if [ "${'$'}CACHED_REFERENCE" == "null" ]; then
                    echo "Commit %git.commit.hash% cached without release notes."
                    exit 0
                  # Second case, artifact was already created and we have cached release notes for it
                  else
                    echo "Commit %git.commit.hash% found in cache, checksum: ${'$'}CACHED_REFERENCE"
                    exit 0
                  fi
                fi
                
                if curl -sf %env.CACHE_MAP% -o tmp_release_notes.txt; then
                  CHECKSUM=${'$'}(sha256sum tmp_release_notes.txt | awk '{print ${'$'}1}')
                  echo "Downloaded release notes, checksum: ${'$'}CHECKSUM"
                  jq --arg commit %git.commit.hash% --arg checksum "${'$'}CHECKSUM" '. + {(${'$'}commit): ${'$'}checksum}' %env.CACHE_MAP% > "%env.CACHE_MAP%.tmp" && mv "%env.CACHE_MAP%.tmp" %env.CACHE_MAP%
                else
                  echo "Website unavailable, storing null in cache"
                  jq --arg commit %git.commit.hash% '. + {(${'$'}commit): null}' %env.CACHE_MAP% > "%env.CACHE_MAP%.tmp" && mv "%env.CACHE_MAP%.tmp" %env.CACHE_MAP%
                fi
            """.trimIndent()
        }
    }

    triggers {
        vcs {
        }
    }

    features {
        perfmon {
        }
    }
})
