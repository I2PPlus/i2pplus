#!/bin/bash

# Comprehensive PMD Violation Fix Script for I2P+
# This script systematically fixes the most common PMD violations across the entire codebase

set -e

echo "=== Comprehensive PMD Violation Fix Script ==="
echo "Starting at $(date)"

# Create results directory
mkdir -p pmd_fix_results
results_file="pmd_fix_results/fix_summary.txt"
echo "PMD Fix Summary - $(date)" > "$results_file"
echo "=================================" >> "$results_file"

# Find all Java files
echo "Finding all Java files..."
find . -name "*.java" -type f ! -path "./.git/*" ! -path "./build/*" ! -path "./.gradle/*" > java_files.txt

total_files=$(cat java_files.txt | wc -l)
echo "Found $total_files Java files to process" | tee -a "$results_file"

processed=0
changes_made=0

# Statistics arrays
declare -A fix_counts
fix_counts[ControlStatementBraces]=0
fix_counts[MissingOverride]=0
fix_counts[EmptyCatchBlock]=0
fix_counts[UselessParentheses]=0
fix_counts[GuardLogStatement]=0
fix_counts[UnnecessaryFullyQualifiedName]=0

echo ""
echo "Processing files..."

while IFS= read -r file; do
    processed=$((processed + 1))
    if [ $((processed % 100)) -eq 0 ]; then
        echo "[$processed/$total_files] Processed $processed files..."
    fi
    
    # Skip if file doesn't exist
    if [ ! -f "$file" ]; then
        continue
    fi
    
    # Create backup only if we make changes
    temp_file=$(mktemp)
    cp "$file" "$temp_file"
    file_changed=false
    
    # Fix 1: ControlStatementBraces - Add braces to control statements
    # This is complex, so we'll handle multiple patterns
    
    # Pattern 1: if (...) return/throw/continue/break;
    if grep -q "if ([^)]*) return [^;]*;" "$temp_file"; then
        sed -i 's/if (\([^)]*\)) return \([^;]*\);/if (\1) {\n        return \2;\n    }/g' "$temp_file"
        file_changed=true
        fix_counts[ControlStatementBraces]=$((${fix_counts[ControlStatementBraces]} + 1))
    fi
    
    if grep -q "if ([^)]*) throw " "$temp_file"; then
        sed -i 's/if (\([^)]*\)) throw \([^;]*\);/if (\1) {\n        throw \2;\n    }/g' "$temp_file"
        file_changed=true
        fix_counts[ControlStatementBraces]=$((${fix_counts[ControlStatementBraces]} + 1))
    fi
    
    if grep -q "if ([^)]*) continue;" "$temp_file"; then
        sed -i 's/if (\([^)]*\)) continue;/if (\1) {\n        continue;\n    }/g' "$temp_file"
        file_changed=true
        fix_counts[ControlStatementBraces]=$((${fix_counts[ControlStatementBraces]} + 1))
    fi
    
    if grep -q "if ([^)]*) break;" "$temp_file"; then
        sed -i 's/if (\([^)]*\)) break;/if (\1) {\n        break;\n    }/g' "$temp_file"
        file_changed=true
        fix_counts[ControlStatementBraces]=$((${fix_counts[ControlStatementBraces]} + 1))
    fi
    
    # Pattern 2: for/while single statements
    if grep -q "for ([^)]*) [^;]*;" "$temp_file"; then
        sed -i 's/for (\([^)]*\)) \([^;]*\);/for (\1) {\n        \2;\n    }/g' "$temp_file"
        file_changed=true
        fix_counts[ControlStatementBraces]=$((${fix_counts[ControlStatementBraces]} + 1))
    fi
    
    if grep -q "while ([^)]*) [^;]*;" "$temp_file"; then
        sed -i 's/while (\([^)]*\)) \([^;]*\);/while (\1) {\n        \2;\n    }/g' "$temp_file"
        file_changed=true
        fix_counts[ControlStatementBraces]=$((${fix_counts[ControlStatementBraces]} + 1))
    fi
    
    # Fix 2: MissingOverride - Add @Override annotations
    # Common interface methods that should have @Override
    override_patterns=(
        "s/^    public boolean hasNext()$/    @Override\n    public boolean hasNext()/g"
        "s/^    public Object next()$/    @Override\n    public Object next()/g"
        "s/^    public void remove()$/    @Override\n    public void remove()/g"
        "s/^    public void close()$/    @Override\n    public void close()/g"
        "s/^    public String toString()$/    @Override\n    public String toString()/g"
        "s/^    public int hashCode()$/    @Override\n    public int hashCode()/g"
        "s/^    public boolean equals(Object /$/    @Override\n    public boolean equals(Object /g"
        "s/^    public int compareTo(/$/    @Override\n    public int compareTo(/g"
    )
    
    for pattern in "${override_patterns[@]}"; do
        if grep -q "$(echo "$pattern" | sed 's/.*public \([^(]*\)(.*/public \1(/')" "$temp_file"; then
            sed -i "$pattern" "$temp_file"
            file_changed=true
            fix_counts[MissingOverride]=$((${fix_counts[MissingOverride]} + 1))
        fi
    done
    
    # Fix 3: EmptyCatchBlock - Add comments to empty catch blocks
    if grep -q "catch ([^)]*) {}" "$temp_file"; then
        sed -i 's/catch (\([^)]*\)) {}/catch (\1) {\n                \/\/ TODO: Handle exception or add comment\n            }/g' "$temp_file"
        file_changed=true
        fix_counts[EmptyCatchBlock]=$((${fix_counts[EmptyCatchBlock]} + 1))
    fi
    
    # Fix 4: UselessParentheses - Remove unnecessary parentheses
    # Pattern 1: return (condition ? value1 : value2);
    sed -i 's/return (\([^?]*\) ? \([^:]*\) : \([^)]*\));/return \1 ? \2 : \3;/g' "$temp_file"
    
    # Pattern 2: if ((condition)) -> if (condition)
    sed -i 's/if ((\([^)]*\)))/if (\1)/g' "$temp_file"
    
    # Pattern 3: while ((condition)) -> while (condition)
    sed -i 's/while ((\([^)]*\)))/while (\1)/g' "$temp_file"
    
    # Pattern 4: Simple expressions in parentheses
    sed -i 's/return (\([^)]*\));/return \1;/g' "$temp_file"
    
    if grep -q "return (" "$temp_file"; then
        file_changed=true
        fix_counts[UselessParentheses]=$((${fix_counts[UselessParentheses]} + 1))
    fi
    
    # Fix 5: GuardLogStatement - Add guards before log statements
    # Pattern: log.debug/info/trace without guards
    if grep -E "\.(debug|info|trace)\(" "$temp_file" | grep -v "if.*log\." > /dev/null 2>&1; then
        # Add guards for debug statements
        sed -i 's/\([[:space:]]*\)\([a-zA-Z_][a-zA-Z0-9_]*\.debug\([^;]*\);\)/\1if (log.isDebugEnabled()) {\n\1    \2\n\1}/g' "$temp_file"
        
        # Add guards for trace statements  
        sed -i 's/\([[:space:]]*\)\([a-zA-Z_][a-zA-Z0-9_]*\.trace\([^;]*\);\)/\1if (log.isTraceEnabled()) {\n\1    \2\n\1}/g' "$temp_file"
        
        file_changed=true
        fix_counts[GuardLogStatement]=$((${fix_counts[GuardLogStatement]} + 1))
    fi
    
    # Fix 6: UnnecessaryFullyQualifiedName - Basic pattern matching
    # This is complex and requires import analysis, but we'll handle common cases
    if grep -q "java\.util\." "$temp_file"; then
        # Check if java.util.* is imported
        if grep -q "import java\.util\.\*" "$temp_file"; then
            # Remove java.util. prefixes if util.* is imported
            sed -i 's/java\.util\.\([A-Z][a-zA-Z0-9]*\)/\1/g' "$temp_file"
            file_changed=true
            fix_counts[UnnecessaryFullyQualifiedName]=$((${fix_counts[UnnecessaryFullyQualifiedName]} + 1))
        fi
    fi
    
    if grep -q "java\.io\." "$temp_file"; then
        if grep -q "import java\.io\.\*" "$temp_file"; then
            sed -i 's/java\.io\.\([A-Z][a-zA-Z0-9]*\)/\1/g' "$temp_file"
            file_changed=true
            fix_counts[UnnecessaryFullyQualifiedName]=$((${fix_counts[UnnecessaryFullyQualifiedName]} + 1))
        fi
    fi
    
    # If changes were made, update the original file
    if [ "$file_changed" = true ]; then
        cp "$file" "$file.backup"
        cp "$temp_file" "$file"
        changes_made=$((changes_made + 1))
    fi
    
    rm "$temp_file"
    
done < java_files.txt

echo ""
echo "=== Processing Complete ==="
echo "Files processed: $processed" | tee -a "$results_file"
echo "Files changed: $changes_made" | tee -a "$results_file"
echo "" | tee -a "$results_file"

# Print fix statistics
echo "Fix Statistics:" | tee -a "$results_file"
for fix_type in "${!fix_counts[@]}"; do
    count=${fix_counts[$fix_type]}
    echo "  $fix_type: $count fixes" | tee -a "$results_file"
done

# Clean up
rm -f java_files.txt

echo ""
echo "=== Running PMD Analysis to Check Improvement ==="
if [ -f "./pmd-bin-7.7.0/bin/pmd" ]; then
    ./pmd-bin-7.7.0/bin/pmd check -d . -R pmd-bin-7.7.0/pmd-ruleset.xml -f text -r pmd_fix_results/pmd_report_after.txt 2>/dev/null || \
    ./pmd-bin-7.7.0/bin/pmd check -d . -R rulesets/java/quickstart.xml -f text -r pmd_fix_results/pmd_report_after.txt 2>/dev/null || \
    echo "PMD analysis failed - check PMD installation"
    
    if [ -f "pmd_fix_results/pmd_report_after.txt" ]; then
        violations_after=$(grep -c "Violation" pmd_fix_results/pmd_report_after.txt 2>/dev/null || echo "0")
        echo "Violations after fix: $violations_after" | tee -a "$results_file"
    fi
else
    echo "PMD binary not found at ./pmd-bin-7.7.0/bin/pmd" | tee -a "$results_file"
fi

echo ""
echo "=== Summary ==="
echo "✓ Processed $processed Java files"
echo "✓ Made changes to $changes_made files"
echo "✓ Backup files created with .backup extension"
echo "✓ Results saved to pmd_fix_results/"
echo ""
echo "Next steps:"
echo "1. Review changes with: git diff --name-only"
echo "2. Test compilation: ./gradlew compileJava"
echo "3. Run tests: ./gradlew test"
echo "4. Commit changes if satisfied: git add . && git commit -m 'Fix common PMD violations'"
echo ""
echo "Fix script completed at $(date)" | tee -a "$results_file"