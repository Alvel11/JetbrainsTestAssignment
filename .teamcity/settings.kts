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
        cache/cache.json
        releases/
    """.trimIndent()

    params {
        text("env.RELEASE_NOTES_URL", "http://nginx/release-notes.txt", readOnly = true, allowEmpty = true)
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
            name = "DownloadReleaseNotes"
            id = "DownloadReleaseNotes"
            scriptContent = """
                INSTALL_DIR="${'$'}HOME/bin"
                mkdir -p "${'$'}INSTALL_DIR"
                
                
                JQ_URL="https://github.com/stedolan/jq/releases/download/jq-1.8.1/jq-linux64"
                curl -L -o "${'$'}INSTALL_DIR/jq" "${'$'}JQ_URL"
                
                chmod +x "${'$'}INSTALL_DIR/jq"
                
                export PATH="${'$'}INSTALL_DIR:${'$'}PATH"
                
                jq --version
                
                mkdir -p cache releases
                
                
                if [ ! -f %env.CACHE_MAP% ]; then
                  echo "Cache not found"
                  echo "{}" > %env.CACHE_MAP%
                fi
                
                CACHED_REFERENCE=${'$'}(jq -r --arg commit %git.commit.hash% '.[${'$'}commit] // empty' %env.CACHE_MAP%)
                echo "Cached reference: ${'$'}CACHED_REFERENCE"
                
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
                
                # Third case, first time of commit so no cache present, website available
                if curl -sf %env.RELEASE_NOTES_URL% -o tmp_release_notes.txt; then
                  CHECKSUM=${'$'}(sha256sum tmp_release_notes.txt | awk '{print ${'$'}1}')
                  echo "Downloaded release notes, checksum: ${'$'}CHECKSUM"
                  jq --arg commit %git.commit.hash% --arg checksum "${'$'}CHECKSUM" '. + {(${'$'}commit): ${'$'}checksum}' %env.CACHE_MAP% > "%env.CACHE_MAP%.tmp" && mv "%env.CACHE_MAP%.tmp" %env.CACHE_MAP%
                  cp tmp_release_notes.txt "releases/${'$'}CHECKSUM.txt"
                # Fourth case, first time commit, website not available
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

    dependencies {
        artifacts(RelativeId("Build")) {
            buildRule = lastSuccessful()
            artifactRules = """
                cache => cache
                releases => releases
            """.trimIndent()
        }
    }
})
