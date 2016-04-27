package com.chanceit.orm;

import org.javalite.activejdbc.Model;
import org.javalite.activejdbc.annotations.Table;
import org.javalite.activejdbc.annotations.CompositePK;

@SuppressWarnings("serial")
@Table("TurnRolls")
@CompositePK({"gameId","playerId","turnNum","rollNum"})
public class TurnRoll extends Model {}
