#
# SonarQube, open source software quality management tool.
# Copyright (C) 2008-2014 SonarSource
# mailto:contact AT sonarsource DOT com
#
# SonarQube is free software; you can redistribute it and/or
# modify it under the terms of the GNU Lesser General Public
# License as published by the Free Software Foundation; either
# version 3 of the License, or (at your option) any later version.
#
# SonarQube is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
# Lesser General Public License for more details.
#
# You should have received a copy of the GNU Lesser General Public License
# along with this program; if not, write to the Free Software Foundation,
# Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
#

#
# SonarQube 6.1
#
class RecreateTableScannerContext < ActiveRecord::Migration

  def self.up
    drop_table 'scanner_context'

    create_table 'ce_scanner_context', :id => false do |t|
      t.column 'task_uuid', :string, :limit => 40, :null => false
      t.column 'data', :binary, :null => false
      t.column 'created_at', :big_integer, :null => false
      t.column 'updated_at', :big_integer, :null => false
    end
    add_primary_key 'ce_scanner_context', 'task_uuid'
  end
end
