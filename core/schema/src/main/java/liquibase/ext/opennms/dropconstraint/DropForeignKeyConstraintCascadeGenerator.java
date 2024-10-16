/*
 * Licensed to The OpenNMS Group, Inc (TOG) under one or more
 * contributor license agreements.  See the LICENSE.md file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * TOG licenses this file to You under the GNU Affero General
 * Public License Version 3 (the "License") or (at your option)
 * any later version.  You may not use this file except in
 * compliance with the License.  You may obtain a copy of the
 * License at:
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package liquibase.ext.opennms.dropconstraint;

import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.sql.Sql;
import liquibase.sql.UnparsedSql;
import liquibase.sqlgenerator.SqlGeneratorChain;
import liquibase.sqlgenerator.core.DropForeignKeyConstraintGenerator;
import liquibase.statement.core.DropForeignKeyConstraintStatement;
import liquibase.structure.DatabaseObject;

public class DropForeignKeyConstraintCascadeGenerator extends DropForeignKeyConstraintGenerator {

    @Override
    public int getPriority() {
        return super.getPriority() + 1;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Sql[] generateSql(final DropForeignKeyConstraintStatement statement, final Database database, final SqlGeneratorChain sqlGeneratorChain) {
        final Sql[] superSql = super.generateSql(statement, database, sqlGeneratorChain);
        if (statement instanceof DropForeignKeyConstraintCascadeStatement) {
            Boolean cascade = ((DropForeignKeyConstraintCascadeStatement)statement).getCascade();
            if (cascade != null && cascade && database instanceof PostgresDatabase) {
                return new Sql[] {
                        new UnparsedSql(superSql[0].toSql() + " CASCADE", superSql[0].getEndDelimiter(), superSql[0].getAffectedDatabaseObjects().toArray(new DatabaseObject[0]))
                };
            } else {
                return superSql;
            }
        } else {
            return superSql;
        }
    }

}
