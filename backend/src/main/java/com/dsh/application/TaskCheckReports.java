package com.dsh.application;

/** Internal raw-report adapter reusing the existing hardened Surefire parser. No success verdict. */
public final class TaskCheckReports {
    private TaskCheckReports() { }
    public record Parsed(TestCounts counts,String error) { }
    public static Parsed parse(byte[] bytes) {
        return parse(bytes,new java.util.HashSet<>());
    }
    private static Parsed parse(byte[] bytes,java.util.Set<String> identities) {
        if(bytes==null || bytes.length>1048576)return new Parsed(new TestCounts(0,0,0,0),"REPORT_BYTE_LIMIT");
        try{return new Parsed(SurefireReports.parse(bytes,identities),null);}
        catch(RuntimeException invalid){String reason=invalid.getMessage();return new Parsed(new TestCounts(0,0,0,0),
                reason!=null&&reason.matches("REPORT_[A-Z_]+")?reason:"REPORT_INVALID_XML");}
    }
    public static Parsed parseAll(java.util.List<byte[]> reports) {
        if(reports==null || reports.isEmpty())return new Parsed(new TestCounts(0,0,0,0),"REPORT_MISSING");
        if(reports.size()>64)return new Parsed(new TestCounts(0,0,0,0),"REPORT_FILE_LIMIT");
        var identities=new java.util.HashSet<String>();int found=0,passed=0,failed=0,skipped=0,total=0;
        try{for(byte[] bytes:reports){
            if(bytes==null || (total=Math.addExact(total,bytes.length))>4194304)
                return new Parsed(new TestCounts(0,0,0,0),"REPORT_BYTE_LIMIT");
            var parsed=parse(bytes,identities);if(parsed.error()!=null)return new Parsed(new TestCounts(0,0,0,0),parsed.error());
            var counts=parsed.counts();found=Math.addExact(found,counts.discovered());passed=Math.addExact(passed,counts.passed());
            failed=Math.addExact(failed,counts.failures());skipped=Math.addExact(skipped,counts.skipped());
        }}catch(ArithmeticException overflow){return new Parsed(new TestCounts(0,0,0,0),"REPORT_COUNT_LIMIT");}
        return new Parsed(new TestCounts(found,passed,failed,skipped),null);
    }
}
