package com.chanceit.orm;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;
import org.javalite.activejdbc.annotations.CompositePK;

@SuppressWarnings("serial")
@Table("Turns")
@CompositePK({"gameId","playerId","turnNum"})
public class Turn extends Model {}
