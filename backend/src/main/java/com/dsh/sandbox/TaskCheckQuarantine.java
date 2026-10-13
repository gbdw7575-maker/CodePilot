package com.dsh.sandbox;

/** A retained name reservation, never an ABSENT or task-success claim. */
public final class TaskCheckQuarantine {
    private final String operationId,containerId,ownerId,imageDigest;
    private final boolean fenced;
    TaskCheckQuarantine(String operation,String container,String owner,String image,boolean fenced){
        operationId=operation;containerId=container;ownerId=owner;imageDigest=image;this.fenced=fenced;
    }
    public String operationId(){return operationId;}
    public String containerId(){return containerId;}
    public String ownerId(){return ownerId;}
    public String imageDigest(){return imageDigest;}
    public boolean fenced(){return fenced;}
}
