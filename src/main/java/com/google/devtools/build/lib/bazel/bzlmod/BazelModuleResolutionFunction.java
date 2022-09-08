// Copyright 2021 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableTable;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.bazel.BazelVersion;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.bzlmod.Selection.SelectionResult;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.LabelConverter;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Runs Bazel module resolution. This function produces the dependency graph containing all Bazel
 * modules, along with a few lookup maps that help with further usage. By this stage, module
 * extensions are not evaluated yet.
 */
public class BazelModuleResolutionFunction implements SkyFunction {

  public static final Precomputed<CheckDirectDepsMode> CHECK_DIRECT_DEPENDENCIES =
      new Precomputed<>("check_direct_dependency");

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws SkyFunctionException, InterruptedException {
    RootModuleFileValue root =
        (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    if (root == null) {
      return null;
    }
    ImmutableMap<ModuleKey, Module> initialDepGraph = Discovery.run(env, root);
    if (initialDepGraph == null) {
      return null;
    }
    ImmutableMap<String, ModuleOverride> overrides = root.getOverrides();
    SelectionResult selectionResult;
    try {
      selectionResult = Selection.run(initialDepGraph, overrides);
    } catch (ExternalDepsException e) {
      throw new BazelModuleResolutionFunctionException(e, Transience.PERSISTENT);
    }
    ImmutableMap<ModuleKey, Module> resolvedDepGraph = selectionResult.getResolvedDepGraph();

    // TODO(salmasamy) add flag to ignore version compatability check
    String currentBazelVersion = BlazeVersionInfo.instance().getVersion();
    if(!Strings.isNullOrEmpty(currentBazelVersion)){
      for(Module module: resolvedDepGraph.values()){
        ImmutableList<String> moduleBazelCompatability = module.getBazelCompatibility();
        for(String compatabilityVersion: moduleBazelCompatability){
          validateVersionCompatability(compatabilityVersion, currentBazelVersion, module.getName());
        }
      }
    }

    verifyRootModuleDirectDepsAreAccurate(
        env, initialDepGraph.get(ModuleKey.ROOT), resolvedDepGraph.get(ModuleKey.ROOT));
    return createValue(resolvedDepGraph, selectionResult.getUnprunedDepGraph(), overrides);
  }

  private void validateVersionCompatability(String comVersion, String currentVersion,
      String moduleName) throws BazelModuleResolutionFunctionException {
    if(currentVersion.isEmpty()) return;

    BazelVersion toCompare, curVer;
    int cutIndex = comVersion.contains("=") ? 2 : 1;
     try {
       toCompare = BazelVersion.parse(comVersion.substring(cutIndex));
       curVer = BazelVersion.parse(currentVersion);
     } catch(ParseException e) {
       throw new BazelModuleResolutionFunctionException(
           ExternalDepsException.withMessage(
               Code.VERSION_RESOLUTION_ERROR, "Bazel compatability check failed: %s",
               e.getMessage()), Transience.PERSISTENT);
     }

     String sign = comVersion.substring(0, cutIndex);
     int result = curVer.compareTo(toCompare);
     if ((result == 0 && !sign.contains("="))
          || (result > 0 && !sign.contains(">") && !sign.equals("-"))
          || (result < 0 && !sign.contains("<") && !sign.equals("-"))
     ){
        throw new BazelModuleResolutionFunctionException(
            ExternalDepsException.withMessage( Code.VERSION_RESOLUTION_ERROR,
                "Bazel version %s is not compatible with module {%s -> bazel_compatability: %s}",
                currentVersion, moduleName, comVersion), Transience.PERSISTENT);
      }
  }

  private static void verifyRootModuleDirectDepsAreAccurate(
      Environment env, Module discoveredRootModule, Module resolvedRootModule)
      throws InterruptedException, BazelModuleResolutionFunctionException {
    CheckDirectDepsMode mode = Objects.requireNonNull(CHECK_DIRECT_DEPENDENCIES.get(env));
    if (mode == CheckDirectDepsMode.OFF) {
      return;
    }
    boolean failure = false;
    for (Map.Entry<String, ModuleKey> dep : discoveredRootModule.getDeps().entrySet()) {
      ModuleKey resolved = resolvedRootModule.getDeps().get(dep.getKey());
      if (!dep.getValue().equals(resolved)) {
        String message =
            String.format(
                "For repository '%s', the root module requires module version %s, but got %s in the"
                    + " resolved dependency graph.",
                dep.getKey(), dep.getValue(), resolved);
        if (mode == CheckDirectDepsMode.WARNING) {
          env.getListener().handle(Event.warn(message));
        } else {
          env.getListener().handle(Event.error(message));
          failure = true;
        }
      }
    }
    if (failure) {
      throw new BazelModuleResolutionFunctionException(
          ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR, "Direct dependency check failed."),
          Transience.PERSISTENT);
    }
  }

  @VisibleForTesting
  static BazelModuleResolutionValue createValue(
      ImmutableMap<ModuleKey, Module> depGraph,
      ImmutableMap<ModuleKey, Module> unprunedDepGraph,
      ImmutableMap<String, ModuleOverride> overrides)
      throws BazelModuleResolutionFunctionException {
    // Build some reverse lookups for later use.
    ImmutableMap<RepositoryName, ModuleKey> canonicalRepoNameLookup =
        depGraph.keySet().stream()
            .collect(toImmutableMap(ModuleKey::getCanonicalRepoName, key -> key));
    ImmutableMap<String, ModuleKey> moduleNameLookup =
        depGraph.keySet().stream()
            // The root module is not meaningfully used by this lookup so we skip it (it's
            // guaranteed to be the first in iteration order).
            .skip(1)
            .filter(key -> !(overrides.get(key.getName()) instanceof MultipleVersionOverride))
            .collect(toImmutableMap(ModuleKey::getName, key -> key));

    // For each extension usage, we resolve (i.e. canonicalize) its bzl file label. Then we can
    // group all usages by the label + name (the ModuleExtensionId).
    ImmutableTable.Builder<ModuleExtensionId, ModuleKey, ModuleExtensionUsage>
        extensionUsagesTableBuilder = ImmutableTable.builder();
    for (Module module : depGraph.values()) {
      LabelConverter labelConverter =
          new LabelConverter(
              PackageIdentifier.create(module.getCanonicalRepoName(), PathFragment.EMPTY_FRAGMENT),
              module.getRepoMappingWithBazelDepsOnly());
      for (ModuleExtensionUsage usage : module.getExtensionUsages()) {
        try {
          ModuleExtensionId moduleExtensionId =
              ModuleExtensionId.create(
                  labelConverter.convert(usage.getExtensionBzlFile()), usage.getExtensionName());
          extensionUsagesTableBuilder.put(moduleExtensionId, module.getKey(), usage);
        } catch (LabelSyntaxException e) {
          throw new BazelModuleResolutionFunctionException(
              ExternalDepsException.withCauseAndMessage(
                  Code.BAD_MODULE,
                  e,
                  "invalid label for module extension found at %s",
                  usage.getLocation()),
              Transience.PERSISTENT);
        }
      }
    }
    ImmutableTable<ModuleExtensionId, ModuleKey, ModuleExtensionUsage> extensionUsagesById =
        extensionUsagesTableBuilder.buildOrThrow();

    // Calculate a unique name for each used extension id.
    BiMap<String, ModuleExtensionId> extensionUniqueNames = HashBiMap.create();
    for (ModuleExtensionId id : extensionUsagesById.rowKeySet()) {
      String bestName =
          id.getBzlFileLabel().getRepository().getName() + "~" + id.getExtensionName();
      if (extensionUniqueNames.putIfAbsent(bestName, id) == null) {
        continue;
      }
      int suffix = 2;
      while (extensionUniqueNames.putIfAbsent(bestName + suffix, id) != null) {
        suffix++;
      }
    }

    return BazelModuleResolutionValue.create(
        depGraph,
        unprunedDepGraph,
        canonicalRepoNameLookup,
        moduleNameLookup,
        depGraph.values().stream().map(AbridgedModule::from).collect(toImmutableList()),
        extensionUsagesById,
        ImmutableMap.copyOf(extensionUniqueNames.inverse()));
  }

  static class BazelModuleResolutionFunctionException extends SkyFunctionException {
    BazelModuleResolutionFunctionException(ExternalDepsException e, Transience transience) {
      super(e, transience);
    }
  }
}
