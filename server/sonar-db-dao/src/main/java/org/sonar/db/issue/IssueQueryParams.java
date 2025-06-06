/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.db.issue;

import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

public class IssueQueryParams {

  private final String projectUuid;
  private final String branchName;
  private final List<String> languages;
  private final List<String> ruleRepositories;
  private final boolean resolvedOnly;
  private final Long changedSince;

  public IssueQueryParams(String projectUuid, String branchName, @Nullable List<String> languages,
    @Nullable List<String> ruleRepositories, boolean resolvedOnly, @Nullable Long changedSince) {
    this.projectUuid = projectUuid;
    this.branchName = branchName;
    this.languages = languages;
    this.ruleRepositories = ruleRepositories;
    this.resolvedOnly = resolvedOnly;
    this.changedSince = changedSince;
  }

  public String getProjectUuid() {
    return projectUuid;
  }

  public String getBranchName() {
    return branchName;
  }

  @CheckForNull
  public List<String> getLanguages() {
    return languages;
  }

  @CheckForNull
  public List<String> getRuleRepositories() {
    return ruleRepositories;
  }

  public boolean isResolvedOnly() {
    return resolvedOnly;
  }

  @CheckForNull
  public Long getChangedSince() {
    return changedSince;
  }
}
