/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.regression.android

import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.gradle.internal.scan.config.fixtures.GradleEnterprisePluginSettingsFixture
import org.gradle.internal.service.scopes.VirtualFileSystemServices
import org.gradle.performance.AbstractCrossBuildPerformanceTest
import org.gradle.performance.categories.PerformanceExperiment
import org.gradle.performance.fixture.BuildExperimentSpec
import org.gradle.performance.fixture.GradleBuildExperimentSpec
import org.gradle.performance.regression.java.JavaInstantExecutionPerformanceTest
import org.gradle.profiler.BuildMutator
import org.gradle.profiler.InvocationSettings
import org.gradle.profiler.ScenarioContext
import org.gradle.profiler.mutations.AbstractCleanupMutator
import org.gradle.profiler.mutations.ClearInstantExecutionStateMutator
import org.gradle.profiler.mutations.ClearProjectCacheMutator
import org.junit.experimental.categories.Category
import spock.lang.Unroll

import static org.gradle.performance.regression.android.IncrementalAndroidTestProject.SANTA_TRACKER_JAVA
import static org.gradle.performance.regression.android.IncrementalAndroidTestProject.SANTA_TRACKER_KOTLIN

@Category(PerformanceExperiment)
class FasterIncrementalAndroidBuildsPerformanceTest extends AbstractCrossBuildPerformanceTest {

    def setup() {
        runner.testGroup = "incremental android changes"
    }

    @Unroll
    def "faster non-abi change on #testProject (build comparison)"() {
        given:
        buildSpecForSupportedOptimizations(testProject) {  name ->
            testProject.configureForNonAbiChange(delegate)
            displayName("non abi change (${name})")
        }

        when:
        def results = runner.run()
        then:
        results
        false // Fail, so the log is archived

        where:
        testProject << [SANTA_TRACKER_KOTLIN, SANTA_TRACKER_JAVA]
    }

    @Unroll
    def "faster abi-change on #testProject (build comparison)"() {
        given:
        buildSpecForSupportedOptimizations(testProject) { name ->
            testProject.configureForAbiChange(delegate)
            displayName("abi change (${name})")
        }

        when:
        def results = runner.run()
        then:
        results

        where:
        testProject << [SANTA_TRACKER_KOTLIN, SANTA_TRACKER_JAVA]
    }

    private void buildSpecForSupportedOptimizations(IncrementalAndroidTestProject testProject, @DelegatesTo(GradleBuildExperimentSpec.GradleBuilder) @ClosureParams(value= SimpleType.class, options="java.lang.String") Closure scenarioConfiguration) {
        supportedOptimizations(testProject).each {name, Set<Optimization> enabledOptimizations ->
            runner.buildSpec {
                passChangedFile(delegate, testProject)
                invocation.args(*enabledOptimizations*.argument)

                final Closure clonedClosure = scenarioConfiguration.clone() as Closure;
                clonedClosure.setResolveStrategy(Closure.DELEGATE_FIRST);
                clonedClosure.setDelegate(delegate);
                clonedClosure.call(name)
            }
        }
    }

    private static Map<String, Set<Optimization>> supportedOptimizations(IncrementalAndroidTestProject testProject) {
        // Kotlin is not supported for instant execution
        return testProject == SANTA_TRACKER_KOTLIN
            ? [
            "no optimizations": EnumSet.noneOf(Optimization),
            "VFS retention": EnumSet.of(Optimization.VFS_RETENTION)
        ]
            : [
            "no optimizations": EnumSet.noneOf(Optimization),
            "VFS retention": EnumSet.of(Optimization.VFS_RETENTION),
            "instant execution": EnumSet.of(Optimization.INSTANT_EXECUTION),
            "all optimizations": EnumSet.allOf(Optimization)
        ]
    }

    @Override
    protected void defaultSpec(BuildExperimentSpec.Builder builder) {
        if (builder instanceof GradleBuildExperimentSpec.GradleBuilder) {
            builder.invocation.args('-Dcom.android.build.gradle.overrideVersionCheck=true')
            builder.invocation.args("-Dorg.gradle.workers.max=8", "--no-build-cache", "--no-scan", "--parallel")
            builder.invocation.useToolingApi()
            builder.invocationCount(100)
            applyEnterprisePlugin(builder)
            builder.addBuildMutator { InvocationSettings invocationSettings ->
                new ClearInstantExecutionStateMutator(invocationSettings.projectDir, AbstractCleanupMutator.CleanupSchedule.SCENARIO)
            }
            builder.addBuildMutator { InvocationSettings invocationSettings ->
                new ClearProjectCacheMutator(invocationSettings.projectDir, AbstractCleanupMutator.CleanupSchedule.SCENARIO)
            }
        }
    }

    static void passChangedFile(GradleBuildExperimentSpec.GradleBuilder builder, IncrementalAndroidTestProject testProject) {
        builder.invocation.args("-D${VirtualFileSystemServices.VFS_CHANGES_SINCE_LAST_BUILD_PROPERTY}=${testProject.pathToChange}")
    }

    enum Optimization {
        INSTANT_EXECUTION(JavaInstantExecutionPerformanceTest.INSTANT_EXECUTION_ENABLED_PROPERTY),
        VFS_RETENTION(VirtualFileSystemServices.VFS_RETENTION_ENABLED_PROPERTY)

        Optimization(String systemProperty) {
            this.argument = "-D${systemProperty}=true"
        }

        final String argument
    }

    void applyEnterprisePlugin(GradleBuildExperimentSpec.GradleBuilder builder) {
        builder.addBuildMutator { invocationSettings ->
            new BuildMutator() {
                String originalSettingsFileText
                final File settingsFile = new File(invocationSettings.projectDir, "settings.gradle")

                @Override
                void beforeScenario(ScenarioContext context) {
                    println("Adding Gradle enterprise plugin to settings file")
                    originalSettingsFileText = settingsFile.text
                    GradleEnterprisePluginSettingsFixture.applyEnterprisePlugin(settingsFile)
                }

                @Override
                void afterScenario(ScenarioContext context) {
                    println("Removing Gradle Enterprise plugin from settings file")
                    settingsFile.text = originalSettingsFileText
                }
            }
        }
    }
}
