// Runs against the production closures through :app:pgoInputsTest; no compiler is invoked.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def property = { value -> new Expando(get: { value }) }
def options = { String role ->
    def values = [buildArgs: ['-O3', '-march=compatibility',
            role == 'Instrument' ? '--pgo-instrument' : "--pgo=${profilePath}".toString()],
        jvmArgs: [], mainClass: 'dev.tuink.app.App',
        imageName: imagePrefix + (role == 'Instrument' ? '-instrument' : ''),
        systemProperties: [:], excludeConfigArgs: [], environmentVariables: [:],
        debug: false, fallback: false, quickBuild: false, sharedLibrary: false,
        useFatJar: false, pgoInstrument: false, verbose: false, richOutput: false]
    def result = new Expando(values.collectEntries { key, value -> [(key): property(value)] })
    result.resources = new Expando(bundles: property([]), includedPatterns: property([]), excludedPatterns: property([]))
    result.layers = []
    result.pgoProfilesDirectory = new Expando(orNull: null)
    result
}
def state = { value -> effectiveCompilerOptions(value) }
def normalized = { value, String role -> normalizeCompilerOptions(state(value), role) }
def copy = { value -> new JsonSlurper().parseText(JsonOutput.toJson(value)) }
def instrument = options('Instrument')
def optimized = options('Optimized')
assert normalized(instrument, 'Instrument') == normalized(optimized, 'Optimized')
assert state(instrument).buildArgs[-1] == '--pgo-instrument' // normalization must not mutate options

// Append an override after the defaults, as BuildNativeImageTask --build-args does.
['-march=native', '-O1', '--gc=G1', '-Dfeature=changed', '-J-Xmx2g'].each { argument ->
    def changed = options('Optimized')
    changed.buildArgs = property(state(changed).buildArgs + [argument])
    assert normalized(instrument, 'Instrument') != normalized(changed, 'Optimized')
    def matching = options('Instrument')
    matching.buildArgs = property(state(matching).buildArgs + [argument])
    assert normalized(matching, 'Instrument') == normalized(changed, 'Optimized')
}

// Ordered user arguments and duplicate options remain part of the fingerprint.
def ordered = copy(state(optimized))
ordered.buildArgs.addAll(['-O1', '-O2'])
def reversed = copy(ordered)
reversed.buildArgs[-2..-1] = ['-O2', '-O1']
assert normalizeCompilerOptions(ordered, 'Optimized') != normalizeCompilerOptions(reversed, 'Optimized')
assert normalizeCompilerOptions(ordered, 'Optimized').buildArgs[-2..-1] == ['-O1', '-O2']

['debug', 'fallback', 'quickBuild', 'sharedLibrary', 'useFatJar', 'verbose', 'richOutput'].each { flag ->
    def changed = options('Optimized')
    changed[flag] = property(true)
    assert normalized(instrument, 'Instrument') != normalized(changed, 'Optimized')
}
[mainClass: 'example.DifferentMain', jvmArgs: ['-Xmx1g'],
    systemProperties: [feature: 'changed'], excludeConfigArgs: ['jar', 'resource']].each { key, value ->
    def changed = options('Optimized')
    changed[key] = property(value)
    assert normalized(instrument, 'Instrument') != normalized(changed, 'Optimized')
}
['NATIVE_IMAGE_OPTIONS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CC', 'CUSTOM_BUILD_ENV'].each { key ->
    def changed = options('Optimized')
    changed.environmentVariables = property([(key): 'changed'])
    assert normalized(instrument, 'Instrument') != normalized(changed, 'Optimized')
    assert !JsonOutput.toJson(normalized(changed, 'Optimized')).contains('CUSTOM_BUILD_ENV')
}
def envLeft = options('Optimized')
def envRight = options('Optimized')
envLeft.environmentVariables = property([A: 'a', B: 'b'])
envRight.environmentVariables = property([B: 'b', A: 'a'])
assert normalized(envLeft, 'Optimized') == normalized(envRight, 'Optimized')

['bundles', 'includedPatterns', 'excludedPatterns'].each { key ->
    def changed = options('Optimized')
    changed.resources[key] = property(['changed'])
    assert normalized(instrument, 'Instrument') != normalized(changed, 'Optimized')
}
def renamed = options('Optimized')
renamed.imageName = property('custom-output')
assert normalized(instrument, 'Instrument') != normalized(renamed, 'Optimized')
def matchingRename = options('Instrument')
matchingRename.imageName = property('custom-output')
assert normalized(matchingRename, 'Instrument') == normalized(renamed, 'Optimized')

// Only each task's single declared selector is removed. Overrides cannot hide.
def reject = { Map value, String role ->
    boolean rejected = false
    try {
        normalizeCompilerOptions(value, role)
    } catch (Exception error) {
        assert error.message.contains('PGO compiler mode was overridden')
        rejected = true
    }
    assert rejected
}
['--pgo=/other/profile.iprof', '--pgo', '--pgo-instrument', '--pgo-instrument=other'].each { argument ->
    def changed = copy(state(optimized))
    changed.buildArgs << argument
    reject(changed, 'Optimized')
}
def duplicate = copy(state(instrument))
duplicate.buildArgs << '--pgo-instrument'
reject(duplicate, 'Instrument')
def missing = copy(state(optimized))
missing.buildArgs.removeLast()
reject(missing, 'Optimized')
def duplicateProfile = copy(state(optimized))
duplicateProfile.buildArgs << "--pgo=${profilePath}".toString()
reject(duplicateProfile, 'Optimized')
// Only the exact role-specific generated directory label is interchangeable.
// Every directory's order, child names, contents and custom path remain checked.
def instrumentConfiguration = [
    [path: 'custom/config', files: ['custom.json': 'custom-hash']],
    [path: 'build/generated/Instrument', files: ['resource-config.json': 'generated-hash']]]
def optimizedConfiguration = [
    [path: 'custom/config', files: ['custom.json': 'custom-hash']],
    [path: 'build/generated/Optimized', files: ['resource-config.json': 'generated-hash']]]
def instrumentNormalized = normalizeConfigurationInputs(instrumentConfiguration, 'build/generated/Instrument')
def optimizedNormalized = normalizeConfigurationInputs(optimizedConfiguration, 'build/generated/Optimized')
assert instrumentNormalized == optimizedNormalized
assert instrumentConfiguration[1].path == 'build/generated/Instrument'
assert instrumentNormalized[0].path == 'custom/config'
def changedConfiguration = copy(optimizedConfiguration)
changedConfiguration[0].path = 'other/custom/config'
assert instrumentNormalized != normalizeConfigurationInputs(changedConfiguration, 'build/generated/Optimized')
changedConfiguration = copy(optimizedConfiguration)
changedConfiguration[1].files['resource-config.json'] = 'changed-bytes'
assert instrumentNormalized != normalizeConfigurationInputs(changedConfiguration, 'build/generated/Optimized')
changedConfiguration = copy(optimizedConfiguration)
changedConfiguration[1].files = ['renamed.json': 'generated-hash']
assert instrumentNormalized != normalizeConfigurationInputs(changedConfiguration, 'build/generated/Optimized')
assert instrumentNormalized != normalizeConfigurationInputs(optimizedConfiguration.reverse(), 'build/generated/Optimized')
assert instrumentNormalized != normalizeConfigurationInputs(optimizedConfiguration, 'different/generated/directory')
def instrumentFlag = copy(state(optimized))
instrumentFlag.flags.pgoInstrument = true
reject(instrumentFlag, 'Optimized')
['layers', 'automaticProfiles'].each { mode ->
    def changed = options('Optimized')
    if (mode == 'layers') {
        changed.layers = ['additional-layer']
    } else {
        changed.pgoProfilesDirectory = new Expando(orNull:
            new Expando(asFileTree: new Expando(files: [new File('untracked.iprof')])))
    }
    boolean rejected = false
    try {
        state(changed)
    } catch (Exception error) {
        assert error.message.contains('one explicit profile')
        rejected = true
    }
    assert rejected
}
println 'PGO compiler input regression checks passed.'
