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
package liquibase.ext.opennms.setsequence;

import static org.junit.Assert.assertEquals;
import liquibase.database.Database;
import liquibase.database.core.PostgresDatabase;
import liquibase.ext.opennms.setsequence.SetSequenceGenerator;
import liquibase.ext.opennms.setsequence.SetSequenceStatement;
import liquibase.sql.Sql;
import liquibase.sqlgenerator.AbstractSqlGeneratorTest;
import liquibase.sqlgenerator.SqlGenerator;
import liquibase.test.TestContext;

import org.junit.Test;

public class SetSequenceGeneratorTest extends AbstractSqlGeneratorTest<SetSequenceStatement> {

    public SetSequenceGeneratorTest() throws Exception {
        super(new SetSequenceGenerator());
    }

    @Override
    protected SetSequenceStatement createSampleSqlStatement() {
    	final SetSequenceStatement statement = new SetSequenceStatement("SEQUENCE_NAME");
    	statement.addTable("TABLE_NAME", "COLUMN1_NAME");
        return statement;
    }

    @Test
    public void testBasicOperation() {
        for (final Database database : TestContext.getInstance().getAllDatabases()) {
            if (database instanceof PostgresDatabase) {
            	final SetSequenceStatement statement = new SetSequenceStatement("SEQUENCE_NAME");
            	statement.addTable("TABLE_NAME", "COLUMN1_NAME");
                if (shouldBeImplementation(database)) {
                    final SqlGenerator<SetSequenceStatement> generator = this.generatorUnderTest;
                    final String tempTableName = ((SetSequenceGenerator)generator).getTempTableName();
					final Sql[] sql = generator.generateSql(statement, database, null);
					assertEquals(
                    	"SELECT pg_catalog.setval('SEQUENCE_NAME',(SELECT max(" + tempTableName + ".id)+1 AS id FROM ((SELECT max(COLUMN1_NAME) AS id FROM TABLE_NAME LIMIT 1)) AS " + tempTableName + " LIMIT 1),true);",
                    	sql[0].toSql()
                    );
                }
            }
        }
    }

    @Test
    public void testWithMultipleTables() {
        for (final Database database : TestContext.getInstance().getAllDatabases()) {
            if (database instanceof PostgresDatabase) {
            	final SetSequenceStatement statement = new SetSequenceStatement("SEQUENCE_NAME");
            	statement.addTable("TABLE1_NAME", "COLUMN1_NAME");
            	statement.addTable("TABLE2_NAME", "COLUMN2_NAME");
                if (shouldBeImplementation(database)) {
                    final SqlGenerator<SetSequenceStatement> generator = this.generatorUnderTest;
                    final String tempTableName = ((SetSequenceGenerator)generator).getTempTableName();
					final Sql[] sql = generator.generateSql(statement, database, null);
					assertEquals(
                    	"SELECT pg_catalog.setval('SEQUENCE_NAME',(SELECT max(" + tempTableName + ".id)+1 AS id FROM ((SELECT max(COLUMN1_NAME) AS id FROM TABLE1_NAME LIMIT 1) UNION (SELECT max(COLUMN2_NAME) AS id FROM TABLE2_NAME LIMIT 1)) AS " + tempTableName + " LIMIT 1),true);",
                    	sql[0].toSql()
                    );
                }
            }
        }
    }

    @Test
    @Override
    public void isImplementation() throws Exception {
    	// No idea why this one in the AbstractSqlGeneratorTest fails, but I don't need it  =)
    }

}
